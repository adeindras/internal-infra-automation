package main

import (
	"context"
	"fmt"

	v2 "k8s.io/api/autoscaling/v2"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
)

// KubernetesHPAProvider validates Kubernetes HPA (v2) resources.
type KubernetesHPAProvider struct{}

func init() {
	providerRegistry["k8s-hpa"] = &KubernetesHPAProvider{}
}

func (p *KubernetesHPAProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	config, err := clientcmd.BuildConfigFromFlags("", kubeconfigPath)
	if err != nil {
		return nil, fmt.Errorf("failed to build kubeconfig: %w", err)
	}
	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		return nil, fmt.Errorf("failed to create kubernetes clientset: %w", err)
	}

	// Use the autoscaling/v2 API group for modern HPA specs
	hpa, err := clientset.AutoscalingV2().HorizontalPodAutoscalers(res.Namespace).Get(context.TODO(), res.Name, metav1.GetOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to get HPA %s in namespace %s: %w", res.Name, res.Namespace, err)
	}

	// Validate MinReplicas
	if expectedMin, ok := res.Spec["minReplicas"].(int); ok {
		if hpa.Spec.MinReplicas == nil || *hpa.Spec.MinReplicas != int32(expectedMin) {
			actualVal := "Not Set"
			if hpa.Spec.MinReplicas != nil {
				actualVal = fmt.Sprintf("%d", *hpa.Spec.MinReplicas)
			}
			diffs = append(diffs, Difference{
				ResourceName: res.Name,
				Provider:     "k8s-hpa",
				Attribute:    "minReplicas",
				Expected:     expectedMin,
				Actual:       actualVal,
			})
		}
	}

	// Validate MaxReplicas
	if expectedMax, ok := res.Spec["maxReplicas"].(int); ok {
		if hpa.Spec.MaxReplicas != int32(expectedMax) {
			diffs = append(diffs, Difference{
				ResourceName: res.Name,
				Provider:     "k8s-hpa",
				Attribute:    "maxReplicas",
				Expected:     expectedMax,
				Actual:       hpa.Spec.MaxReplicas,
			})
		}
	}

	// Validate Metrics
	if expectedMetrics, ok := res.Spec["metrics"].([]interface{}); ok {
		if len(expectedMetrics) != len(hpa.Spec.Metrics) {
			diffs = append(diffs, Difference{
				ResourceName: res.Name,
				Provider:     "k8s-hpa",
				Attribute:    "metrics.count",
				Expected:     len(expectedMetrics),
				Actual:       len(hpa.Spec.Metrics),
			})
		} else {
			// Loop and compare each metric, assuming order is consistent
			for i, expectedMetricIntf := range expectedMetrics {
				expectedMetric := expectedMetricIntf.(map[string]interface{})
				actualMetric := hpa.Spec.Metrics[i]
				diffs = append(diffs, validateHPAMetric(res.Name, i, expectedMetric, actualMetric)...)
			}
		}
	}

	return diffs, nil
}

// validateHPAMetric is a helper to validate a single metric spec within an HPA.
func validateHPAMetric(resName string, index int, expected map[string]interface{}, actual v2.MetricSpec) []Difference {
	var diffs []Difference
	prefix := fmt.Sprintf("metrics[%d]", index)

	// Check Metric Type (e.g., "Resource")
	if expectedType, ok := expected["type"].(string); ok {
		if string(actual.Type) != expectedType {
			diffs = append(diffs, Difference{
				ResourceName: resName,
				Provider:     "k8s-hpa",
				Attribute:    fmt.Sprintf("%s.type", prefix),
				Expected:     expectedType,
				Actual:       string(actual.Type),
			})
			return diffs // Stop checking this metric if type is wrong
		}
	}

	// Check Resource Metric details
	if actual.Type == v2.ResourceMetricSourceType {
		if expectedResource, ok := expected["resource"].(map[string]interface{}); ok {
			// Check resource name (e.g., "cpu")
			if expectedName, ok := expectedResource["name"].(string); ok {
				if string(actual.Resource.Name) != expectedName {
					diffs = append(diffs, Difference{
						ResourceName: resName,
						Provider:     "k8s-hpa",
						Attribute:    fmt.Sprintf("%s.resource.name", prefix),
						Expected:     expectedName,
						Actual:       string(actual.Resource.Name),
					})
				}
			}
			// Check target details
			if expectedTarget, ok := expectedResource["target"].(map[string]interface{}); ok {
				if expectedTargetType, ok := expectedTarget["type"].(string); ok {
					if string(actual.Resource.Target.Type) != expectedTargetType {
						diffs = append(diffs, Difference{
							ResourceName: resName,
							Provider:     "k8s-hpa",
							Attribute:    fmt.Sprintf("%s.resource.target.type", prefix),
							Expected:     expectedTargetType,
							Actual:       string(actual.Resource.Target.Type),
						})
					}
				}
				if expectedAvg, ok := expectedTarget["averageUtilization"].(int); ok {
					if actual.Resource.Target.AverageUtilization == nil || *actual.Resource.Target.AverageUtilization != int32(expectedAvg) {
						actualVal := "Not Set"
						if actual.Resource.Target.AverageUtilization != nil {
							actualVal = fmt.Sprintf("%d", *actual.Resource.Target.AverageUtilization)
						}
						diffs = append(diffs, Difference{
							ResourceName: resName,
							Provider:     "k8s-hpa",
							Attribute:    fmt.Sprintf("%s.resource.target.averageUtilization", prefix),
							Expected:     expectedAvg,
							Actual:       actualVal,
						})
					}
				}
			}
		}
	}
	return diffs
}
