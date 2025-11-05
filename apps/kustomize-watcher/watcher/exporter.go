package watcher

import (
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

var (
	outputVar string
	mu        sync.RWMutex
)

func StartHTTPServer(port string) {
	http.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		mu.RLock()
		defer mu.RUnlock()
		fmt.Fprintf(w, "%s", outputVar)
	})

	log.Printf("Starting server on port %s...\n", port)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

func WatcherInit(kustomization_name []string, namespace string, outputValue []string) {
	for {
		var outputBuilder strings.Builder

		for index, ks_name := range kustomization_name {
			resourceVersion, resourcePatch := GetResourceDetails(namespace, ks_name)
			line := fmt.Sprintf("ab_kustomize_watcher{environment_name:%s;customer_name:%s;account_id:%s;region:%s;resource:%s;version:%s;patch:%s}\n",
				outputValue[0], outputValue[1], outputValue[2], outputValue[3], kustomization_name[index], resourceVersion, resourcePatch)
			outputBuilder.WriteString(line)
		}

		mu.Lock()
		outputVar = outputBuilder.String()
		mu.Unlock()

		time.Sleep(30 * time.Second)
	}
}
