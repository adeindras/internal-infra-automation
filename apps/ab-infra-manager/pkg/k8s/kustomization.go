package k8s

import (
	"context"
	"log/slog"

	kustomizev1beta2 "github.com/fluxcd/kustomize-controller/api/v1beta2"
	v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/dynamic"
)

func GetKustomizationPath(clientset *dynamic.DynamicClient, namespace string, ks string) (string, error) {
	kustomizeUnstructured, err := clientset.
		Resource(kustomizev1beta2.GroupVersion.WithResource("kustomizations")).
		Namespace(namespace).
		Get(context.Background(), ks, v1.GetOptions{})
	if err != nil {
		slog.Error("error getting kustomization", slog.Any("Kustomization", ks), slog.Any("Error", err))
		return "", err
	}

	kustomization := kustomizev1beta2.Kustomization{}
	err = runtime.DefaultUnstructuredConverter.
		FromUnstructured(kustomizeUnstructured.UnstructuredContent(), &kustomization)
	if err != nil {
		slog.Error("error converting kustomization schema to structured data", slog.Any("Kustomization", ks), slog.Any("Error", err))
		return "", err
	}

	return kustomization.Spec.Path, nil
}
