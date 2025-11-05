package main

import (
	"context"
	"fmt"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
)

// KubernetesMeshProvider validates Linkerd proxy specs via annotations on a Deployment.
type KubernetesMeshProvider struct{}

func init() {
	providerRegistry["k8s-mesh"] = &KubernetesMeshProvider{}
}

// Annotation keys for Linkerd proxy resource specs.
const (
	cpuLimitAnnotation      = "config.linkerd.io/proxy-cpu-limit"
	memoryLimitAnnotation   = "config.linkerd.io/proxy-memory-limit"
	cpuRequestAnnotation    = "config.linkerd.io/proxy-cpu-request"
	memoryRequestAnnotation = "config.linkerd.io/proxy-memory-request"
)

func (p *KubernetesMeshProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	config, err := clientcmd.BuildConfigFromFlags("", kubeconfigPath)
	if err != nil {
		return nil, fmt.Errorf("failed to build kubeconfig: %w", err)
	}
	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		return nil, fmt.Errorf("failed to create kubernetes clientset: %w", err)
	}

	deployment, err := clientset.AppsV1().Deployments(res.Namespace).Get(context.TODO(), res.Name, metav1.GetOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to get deployment %s in namespace %s: %w", res.Name, res.Namespace, err)
	}

	annotations := deployment.Spec.Template.GetAnnotations()

	if expectedResourcesSpec, ok := res.Spec["resources"].(map[string]interface{}); ok {
		// Check Requests
		if expectedRequests, ok := expectedResourcesSpec["requests"].(map[string]interface{}); ok {
			if cpu, ok := expectedRequests["cpu"].(string); ok {
				diffs = append(diffs, checkAnnotation(res.Name, "resources.requests.cpu", cpu, annotations, cpuRequestAnnotation)...)
			}
			if memory, ok := expectedRequests["memory"].(string); ok {
				diffs = append(diffs, checkAnnotation(res.Name, "resources.requests.memory", memory, annotations, memoryRequestAnnotation)...)
			}
		}

		// Check Limits
		if expectedLimits, ok := expectedResourcesSpec["limits"].(map[string]interface{}); ok {
			if cpu, ok := expectedLimits["cpu"].(string); ok {
				diffs = append(diffs, checkAnnotation(res.Name, "resources.limits.cpu", cpu, annotations, cpuLimitAnnotation)...)
			}
			if memory, ok := expectedLimits["memory"].(string); ok {
				diffs = append(diffs, checkAnnotation(res.Name, "resources.limits.memory", memory, annotations, memoryLimitAnnotation)...)
			}
		}
	}

	return diffs, nil
}

// checkAnnotation is a helper to compare an expected value against a key in the annotations map.
func checkAnnotation(resName, attribute, expectedVal string, actualAnnotations map[string]string, annotationKey string) []Difference {
	var diffs []Difference
	actualVal, exists := actualAnnotations[annotationKey]
	if !exists || actualVal != expectedVal {
		actualValForReport := "Not Set"
		if exists {
			actualValForReport = actualVal
		}
		diffs = append(diffs, Difference{
			ResourceName: resName,
			Provider:     "k8s-mesh",
			Attribute:    attribute,
			Expected:     expectedVal,
			Actual:       actualValForReport,
		})
	}
	return diffs
}
