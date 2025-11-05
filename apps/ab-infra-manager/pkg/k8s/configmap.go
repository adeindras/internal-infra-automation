package k8s

import (
	"context"
	"log/slog"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

func GetConfigmap(clientset *kubernetes.Clientset, namespace string, configMapName string) (map[string]string, error) {
	configMap, err := clientset.CoreV1().ConfigMaps(namespace).Get(context.TODO(), configMapName, metav1.GetOptions{})
	if err != nil {
		slog.Error("failed to get configmap in namespace", slog.Any("Error", err))
		return nil, err
	}

	return configMap.Data, nil
}
