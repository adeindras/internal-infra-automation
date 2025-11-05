package main

import (
	"kustomize-watcher/watcher"
	"log"
)

func main() {
	// define variable
	namespace := "flux-system"
	outputValue := []string{}
	kustomization_name := []string{
		"linkerd",
		"alb-controller",
		"karpenter",
		"emissary-ingress",
	}

	lookupValue := []string{
		"ENVIRONMENT_NAME",
		"CUSTOMER_NAME",
		"AWS_ACCOUNT_ID",
		"AWS_REGION",
	}

	// init connection
	clientset, err := watcher.KubeConnInit()
	if err != nil {
		panic(err.Error())
	}

	// cluster information
	for _, value := range lookupValue {
		value, err := watcher.GetConfigmapValue(clientset, namespace, "cluster-variables", value)
		if err != nil {
			log.Fatalf("Error retrieving value from ConfigMap: %v", err)
		}

		outputValue = append(outputValue, value)
	}

	// start the watcher on separate goroutine
	go watcher.WatcherInit(kustomization_name, namespace, outputValue)

	// init https server
	watcher.StartHTTPServer("8080")

}
