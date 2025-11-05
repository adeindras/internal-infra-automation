package k8s

import (
	"log/slog"
	"path/filepath"

	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
)

func GetKubeConfig() (*rest.Config, error) {
	config := &rest.Config{}
	err := error(nil)

	// Try to get ClientSet from Inside the cluster first,
	// If failed, try to use local config
	config, err = rest.InClusterConfig()
	if err != nil {
		slog.Error("Fail to build k8s in cluster config.", slog.Any("Error", err))

		// Try to get k8s config from local home directory
		homeDir := homedir.HomeDir()
		kubeconfig := filepath.Join(homeDir, ".kube", "config")
		config, err = clientcmd.BuildConfigFromFlags("", kubeconfig)
		if err != nil {
			slog.Error("Fail to build k8s config from local file.", slog.Any("Error", err))
			return nil, err
		}
	}

	return config, nil
}

func GetK8sClientSet(c *rest.Config) (*kubernetes.Clientset, error) {
	clientSet, err := kubernetes.NewForConfig(c)
	if err != nil {
		slog.Error("Fail to initiate new k8s clientset.", slog.Any("Error", err))
		return nil, err
	}
	return clientSet, nil
}

func GetK8sDynamicClientSet(c *rest.Config) (*dynamic.DynamicClient, error) {
	// build dynamic client set
	dynamicClientSet, err := dynamic.NewForConfig(c)
	if err != nil {
		slog.Error("Fail to initiate new dynamic k8s clientset.", slog.Any("Error", err))
		return nil, err
	}
	return dynamicClientSet, nil
}
