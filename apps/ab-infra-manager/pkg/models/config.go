package models

import (
	"time"

	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
)

// Cfg struct to store config object
type Cfg struct {
	CCU                 int64
	Live                bool
	K8sClientSet        *kubernetes.Clientset
	K8sDynamicClientSet *dynamic.DynamicClient
	KsResources         []string
	KsNamespace         string
	LogLevel            int

	AWSProfile             string
	AWSUsageScrapeInterval time.Duration
	AWSUsageTimeRange      time.Duration
}
