package watcher

import (
	"context"
	"fmt"

	kustomizev1beta2 "github.com/fluxcd/kustomize-controller/api/v1beta2"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

func GetPodList(namespace string, client kubernetes.Interface) (*v1.PodList, error) {
	pods, err := client.CoreV1().Pods(namespace).List(context.Background(), metav1.ListOptions{})
	if err != nil {
		err = fmt.Errorf("error getting pods: %v\n", err)
		return nil, err
	}
	return pods, nil
}

func GetNamespaceList(client kubernetes.Interface) (*v1.NamespaceList, error) {
	namespaces, err := client.CoreV1().Namespaces().List(context.Background(), metav1.ListOptions{})
	if err != nil {
		err = fmt.Errorf("error getting namespaces: %v\n", err)
		return nil, err
	}
	return namespaces, nil
}

func GetConfigmapList(namespace string, client kubernetes.Interface) (*v1.ConfigMapList, error) {
	configmap, err := client.CoreV1().ConfigMaps(namespace).List(context.Background(), metav1.ListOptions{})
	if err != nil {
		err = fmt.Errorf("error getting configmap: %v\n", err)
		return nil, err
	}
	return configmap, nil
}

func GetConfigmapValue(clientset *kubernetes.Clientset, namespace, configMapName, key string) (string, error) {
    configMap, err := clientset.CoreV1().ConfigMaps(namespace).Get(context.TODO(), configMapName, metav1.GetOptions{})
    if err != nil {
        return "", fmt.Errorf("failed to get ConfigMap %s in namespace %s: %v", configMapName, namespace, err)
    }

    value, exists := configMap.Data[key]
    if !exists {
        return "", fmt.Errorf("key %s not found in ConfigMap %s", key, configMapName)
    }

    return value, nil
}

func GetKustomizationList(namespace string) (*kustomizev1beta2.KustomizationList, error) {
	connInit, err := CustomConnInit()
	if err != nil {
		return nil, fmt.Errorf("failed to connect to Kubernetes client: %v", err)
	}

	kustomizationList := &kustomizev1beta2.KustomizationList{}

	listOptions := &client.ListOptions{Namespace: namespace}

	err = connInit.List(context.Background(), kustomizationList, listOptions)
	if err != nil {
		return nil, fmt.Errorf("failed to list Kustomizations in namespace %s: %v", namespace, err)
	}

	return kustomizationList, nil
}

func GetKustomizationPath(namespace, ks string) (string, error) {
	connInit, err := CustomConnInit()
	if err != nil {
		return "", fmt.Errorf("failed to connect to Kubernetes client: %v", err)
	}

	kustomization := &kustomizev1beta2.Kustomization{}
	err = connInit.Get(context.Background(), client.ObjectKey{
		Namespace: namespace,
		Name:      ks,
	}, kustomization)
	if err != nil {
		return "", fmt.Errorf("failed to get Kustomization %s in namespace %s: %v", ks, namespace, err)
	}

	return kustomization.Spec.Path, nil
}
