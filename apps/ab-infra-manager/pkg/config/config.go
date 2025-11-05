package config

import (
	"accelbyte/ab-infra-manager/pkg/k8s"
	"accelbyte/ab-infra-manager/pkg/models"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"
)

// Load configuration
func Load() (models.Cfg, error) {
	c := models.Cfg{}

	ccu, err := strconv.Atoi(os.Getenv("CCU"))
	if err != nil {
		return c, fmt.Errorf("CCU environment variable must be set")
	}
	c.CCU = int64(ccu)

	live, err := strconv.ParseBool(os.Getenv("LIVE"))
	if err != nil {
		return c, fmt.Errorf("LIVE environment variable must be set")
	}
	c.Live = live

	ksResources := os.Getenv("KUSTOMIZATION_RESOURCES")
	if ksResources == "" {
		return c, fmt.Errorf("KUSTOMIZATION_RESOURCES environment variable must be set with comma separated")
	}
	s := strings.Split(ksResources, ",")
	c.KsResources = s

	ksNamespace := os.Getenv("KUSTOMIZATION_NAMESPACE")
	if ksNamespace == "" {
		slog.Info("KUSTOMIZATION_NAMESPACE envvar not found, defaulting to flux-system")
		ksNamespace = "flux-system"
	}
	c.KsNamespace = ksNamespace

	logLevel := os.Getenv("LOG_LEVEL")
	if logLevel == "" {
		logLevel = "INFO"
	}
	slogLevel := &slog.LevelVar{}
	err = slogLevel.UnmarshalText([]byte(logLevel))
	if err != nil {
		return c, fmt.Errorf("invalid log level")
	}
	c.LogLevel = int(slogLevel.Level())

	c.AWSProfile = os.Getenv("AWS_PROFILE")

	k8sConfig, err := k8s.GetKubeConfig()
	if err != nil {
		return c, fmt.Errorf("error getting k8s config")
	}
	c.K8sClientSet, err = k8s.GetK8sClientSet(k8sConfig)
	if err != nil {
		return c, fmt.Errorf("error initializing k8s client set")
	}
	c.K8sDynamicClientSet, err = k8s.GetK8sDynamicClientSet(k8sConfig)
	if err != nil {
		return c, fmt.Errorf("error initializing k8s dynamic client set")
	}

	awsUsageScrapeIntervalEnv := os.Getenv("AWS_USAGE_SCRAPE_INTERVAL")
	if awsUsageScrapeIntervalEnv == "" {
		awsUsageScrapeIntervalEnv = "24h"
	}
	awsUsageScrapeInterval, err := time.ParseDuration(awsUsageScrapeIntervalEnv)
	if err != nil {
		return c, fmt.Errorf("error parsing AWS_USAGE_SCRAPE_INTERVAL")
	}
	c.AWSUsageScrapeInterval = awsUsageScrapeInterval

	awsUsageTimeRangeEnv := os.Getenv("AWS_USAGE_TIME_RANGE")
	if awsUsageTimeRangeEnv == "" {
		awsUsageTimeRangeEnv = "336h"
	}
	awsUsageTimeRange, err := time.ParseDuration(awsUsageTimeRangeEnv)
	if err != nil {
		return c, fmt.Errorf("error parsing AWS_USAGE_TIME_RANGE")
	}
	c.AWSUsageTimeRange = awsUsageTimeRange

	return c, nil
}
