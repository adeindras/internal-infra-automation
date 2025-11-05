package main

import (
	"context"
	"fmt"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
)

// KubernetesDeploymentProvider validates Kubernetes Deployments.
type KubernetesDeploymentProvider struct{}

func init() {
	providerRegistry["k8s-deployment"] = &KubernetesDeploymentProvider{}
}

func (p *KubernetesDeploymentProvider) Validate(res Resource) ([]Difference, error) {
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

	// Validate Replicas
	if expectedReplicas, ok := res.Spec["replicas"].(int); ok {
		if *deployment.Spec.Replicas != int32(expectedReplicas) {
			diffs = append(diffs, Difference{
				ResourceName: res.Name,
				Provider:     "k8s-deployment",
				Attribute:    "Replicas",
				Expected:     expectedReplicas,
				Actual:       *deployment.Spec.Replicas,
			})
		}
	}

	// ✅ Validate Container Resources (for the first container)
	if expectedResourcesSpec, ok := res.Spec["resources"].(map[string]interface{}); ok {
		if len(deployment.Spec.Template.Spec.Containers) > 0 {
			actualResources := deployment.Spec.Template.Spec.Containers[0].Resources
			diffs = append(diffs, checkResourceMap(res.Name, expectedResourcesSpec, "limits", actualResources.Limits)...)
			diffs = append(diffs, checkResourceMap(res.Name, expectedResourcesSpec, "requests", actualResources.Requests)...)
		}
	}

	return diffs, nil
}

// checkResourceMap is a helper to validate a resource map (limits or requests).
func checkResourceMap(resName string, expectedSpec map[string]interface{}, resourceType string, actualList corev1.ResourceList) []Difference {
	var diffs []Difference
	expectedMap, ok := expectedSpec[resourceType].(map[string]interface{})
	if !ok {
		return diffs // No spec for this type (e.g., no 'limits' block), so nothing to check.
	}

	// Check CPU
	if expectedCPU, ok := expectedMap["cpu"].(string); ok {
		if actualCPU, exists := actualList[corev1.ResourceCPU]; !exists || actualCPU.String() != expectedCPU {
			actualVal := "Not Set"
			if exists {
				actualVal = actualCPU.String()
			}
			diffs = append(diffs, Difference{
				ResourceName: resName,
				Provider:     "k8s-deployment",
				Attribute:    fmt.Sprintf("resources.%s.cpu", resourceType),
				Expected:     expectedCPU,
				Actual:       actualVal,
			})
		}
	}

	// Check Memory
	if expectedMemory, ok := expectedMap["memory"].(string); ok {
		if actualMemory, exists := actualList[corev1.ResourceMemory]; !exists || actualMemory.String() != expectedMemory {
			actualVal := "Not Set"
			if exists {
				actualVal = actualMemory.String()
			}
			diffs = append(diffs, Difference{
				ResourceName: resName,
				Provider:     "k8s-deployment",
				Attribute:    fmt.Sprintf("resources.%s.memory", resourceType),
				Expected:     expectedMemory,
				Actual:       actualVal,
			})
		}
	}
	return diffs
}
