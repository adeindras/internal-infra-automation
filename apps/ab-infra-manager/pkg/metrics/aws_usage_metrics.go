// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.

package metrics

import (
	"accelbyte/ab-infra-manager/pkg/models"
	"accelbyte/ab-infra-manager/pkg/usagestore"
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/prometheus/client_golang/prometheus"
)

type AWSUsageCollector struct {
	CustomerName    string
	EnvironmentName string
	ProjectName     string

	awsUsageStorage *usagestore.UsageStore
	isStopFetching  bool // a flag that will be set to true if we got 401 or 403 which means misconfiguration and avoid unecessary aws api call
}

func NewAWSUsageCollector(config models.Cfg, awsConfig aws.Config, customerName, environtmentName, projectName string) *AWSUsageCollector {
	return &AWSUsageCollector{
		CustomerName:    customerName,
		EnvironmentName: environtmentName,
		ProjectName:     projectName,
		awsUsageStorage: usagestore.New(config, awsConfig, map[string]string{
			"customer_name":    customerName,
			"project":          projectName,
			"environment_name": environtmentName,
		}, fmt.Sprintf("%s-%s-%s", customerName, projectName, environtmentName)),
	}
}

func (u *AWSUsageCollector) ValidateFetchable(err error) {
	if strings.Contains(err.Error(), "StatusCode: 403") || strings.Contains(err.Error(), "StatusCode: 401") {
		u.isStopFetching = true
	}
}
func (u *AWSUsageCollector) Describe(ch chan<- *prometheus.Desc) {
	prometheus.DescribeByCollect(u, ch)
}

func (u *AWSUsageCollector) Collect(ch chan<- prometheus.Metric) {
	ctx := context.Background()

	for _, metric := range u.scrape(ctx) {
		ch <- metric
	}
}

func (u *AWSUsageCollector) scrape(ctx context.Context) []prometheus.Metric {
	var collectedMetrics []prometheus.Metric
	if u.isStopFetching {
		log.ErrorContext(ctx, "configuration error, invalid AWS credentials")
		return collectedMetrics
	}
	instances, err := u.awsUsageStorage.GetInstances(ctx)
	if err != nil {
		u.ValidateFetchable(err)
	}
	for _, instance := range instances {
		identifier := instance.Identifier
		if instance.Level == "Broker" {
			identifier = identifier + "-" + instance.BrokerID
		}
		maxCPUUsage, err := u.awsUsageStorage.GetMaxCPUUsage(ctx, identifier, instance)
		if err != nil {
			u.ValidateFetchable(err)
			log.ErrorContext(ctx, "failed to get db usage",
				"error", err,
				"identifier", instance.Identifier,
				"engine", instance.Engine,
				"level", instance.Level,
				"identifier_field_name", instance.IdentifierFieldName,
				"instance_class", instance.InstanceClass,
			)
			continue
		}
		collectedMetrics = append(collectedMetrics, prometheus.MustNewConstMetric(
			prometheus.NewDesc(
				prometheus.BuildFQName("aws", "resource_usage", "cpu_max"), "Resource peak CPU Usage",
				[]string{"identifier", "instance_class", "engine", "level", "collected_time", "environment"},
				nil,
			),
			prometheus.GaugeValue,
			maxCPUUsage,
			identifier, instance.InstanceClass, instance.Engine, instance.Level, instance.CollectedTime.Format(time.RFC3339),
			fmt.Sprintf("%s-%s-%s", u.CustomerName, u.ProjectName, u.EnvironmentName),
		))

		if instance.Engine == "redis" || instance.Engine == "valkey" {
			maxMemoryUsage, err := u.awsUsageStorage.GetElastiCacheMaxMemoryUsage(ctx, identifier, instance)
			if err != nil {
				u.ValidateFetchable(err)
				log.ErrorContext(ctx, "failed to get db usage",
					"identifier", instance.Identifier,
					"error", err,
					"engine", instance.Engine,
					"level", instance.Level,
					"identifier_field_name", instance.IdentifierFieldName,
					"instance_class", instance.InstanceClass,
				)
				continue
			}
			collectedMetrics = append(collectedMetrics, prometheus.MustNewConstMetric(
				prometheus.NewDesc(
					prometheus.BuildFQName("aws", "resource_usage", "memory_max"), "Resource peak memory Usage",
					[]string{"identifier", "instance_class", "engine", "level", "collected_time", "environment"},
					nil,
				),
				prometheus.GaugeValue,
				maxMemoryUsage,
				identifier, instance.InstanceClass, instance.Engine, instance.Level, instance.CollectedTime.Format(time.RFC3339),
				fmt.Sprintf("%s-%s-%s", u.CustomerName, u.ProjectName, u.EnvironmentName),
			))
		}
	}

	return collectedMetrics
}
