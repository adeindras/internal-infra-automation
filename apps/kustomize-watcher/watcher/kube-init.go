package watcher

import (
	"flag"
	"fmt"
	"path/filepath"

	kustomizev1beta2 "github.com/fluxcd/kustomize-controller/api/v1beta2"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/config"
)

func KubeConnInit() (*kubernetes.Clientset, error) {
	var config *rest.Config
	var err error

	if config, err = rest.InClusterConfig(); err != nil {
		var kubecon *string
		if home := homedir.HomeDir(); home != "" {
			kubecon = flag.String("kubecon", filepath.Join(home, ".kube", "config"), "(optional) absolute path to the kubeconfig file")
		} else {
			kubecon = flag.String("kubecon", "", "absolute path to the kubeconfig file")
		}
		flag.Parse()

		config, err = clientcmd.BuildConfigFromFlags("", *kubecon)
		if err != nil {
			return nil, err
		}
	}

	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		return nil, err
	}

	return clientset, nil
}

func CustomConnInit() (client.Client, error) {
	err := kustomizev1beta2.AddToScheme(scheme.Scheme)
	if err != nil {
		return nil, fmt.Errorf("failed to add Kustomization scheme: %v", err)
	}

	var cfg *rest.Config
	cfg, err = rest.InClusterConfig()

	if err != nil {
		cfg, err = config.GetConfig()
		if err != nil {
			return nil, fmt.Errorf("failed to get Kubernetes config: %v", err)
		}
	}

	k8sClient, err := client.New(cfg, client.Options{Scheme: scheme.Scheme})
	if err != nil {
		return nil, fmt.Errorf("failed to create Kubernetes client: %v", err)
	}

	return k8sClient, nil
}
