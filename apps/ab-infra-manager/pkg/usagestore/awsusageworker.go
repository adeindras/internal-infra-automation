// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.

package usagestore

import (
	"accelbyte/ab-infra-manager/pkg/cache"
	"accelbyte/ab-infra-manager/pkg/models"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatch"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatch/types"
	"github.com/aws/aws-sdk-go-v2/service/docdb"
	"github.com/aws/aws-sdk-go-v2/service/elasticache"
	"github.com/aws/aws-sdk-go-v2/service/kafka"
	"github.com/aws/aws-sdk-go-v2/service/rds"
)

var log = slog.Default()

type InstanceInfo struct {
	Identifier          string
	InstanceClass       string
	Engine              string
	Level               string
	IdentifierFieldName string
	BrokerID            string
	BrokerCount         int // specific for Kafka Broker
	CollectedTime       time.Time
}

type UsageStore struct {
	EnvironmentName string
	AWSTags         map[string]string

	config      *models.Cfg
	infoStore   *cache.Cache[string, InstanceInfo]
	metricStore *cache.Cache[string, float64]

	cloudwatchClient  *cloudwatch.Client
	rdsClient         *rds.Client
	docdbClient       *docdb.Client
	elasticacheClient *elasticache.Client
	kafkaClient       *kafka.Client
}

func New(cfg models.Cfg, awsConfig aws.Config, awsTags map[string]string, environmentName string) *UsageStore {
	return &UsageStore{
		config:            &cfg,
		AWSTags:           awsTags,
		EnvironmentName:   environmentName,
		infoStore:         cache.New[string, InstanceInfo](),
		metricStore:       cache.New[string, float64](),
		cloudwatchClient:  cloudwatch.NewFromConfig(awsConfig),
		rdsClient:         rds.NewFromConfig(awsConfig),
		docdbClient:       docdb.NewFromConfig(awsConfig),
		elasticacheClient: elasticache.NewFromConfig(awsConfig),
		kafkaClient:       kafka.NewFromConfig(awsConfig),
	}
}

func (u *UsageStore) GetInstances(ctx context.Context) ([]InstanceInfo, error) {
	var err error
	log.DebugContext(ctx, "info store", "len", u.infoStore.Length(), "expired", u.infoStore.IsExpired())
	if u.infoStore.Length() == 0 || u.infoStore.IsExpired() {
		err = u.scrapeRDSInfo(ctx)
		if err != nil {
			return nil, err
		}
		err = u.scrapeDocDBInfo(ctx)
		if err != nil {
			return nil, err
		}
		err = u.scrapeElastiCacheInfo(ctx)
		if err != nil {
			return nil, err
		}
		err = u.scrapeKafkaInfo(ctx)
		if err != nil {
			return nil, err
		}
	}
	instances := make([]InstanceInfo, 0)

	for _, k := range u.infoStore.Keys() {
		instance, ok := u.infoStore.Get(k)
		if !ok {
			continue
		}
		instances = append(instances, instance)
	}
	return instances, nil
}

func (u *UsageStore) metricStoreGetter(ctx context.Context, key string, metricInput *cloudwatch.GetMetricStatisticsInput) func(string) (float64, error) {
	if _, ok := u.metricStore.Get(key); ok {
		return func(s string) (float64, error) {
			val, _ := u.metricStore.Get(s)
			return val, nil
		}
	}
	return func(s string) (float64, error) {
		val, err := u.getCWMetricsMax(ctx, metricInput)
		if err != nil {
			log.ErrorContext(ctx, "failed to scrape cloudwatch metrics", "error", err)
			return 0, err
		}
		u.metricStore.Set(s, val, u.config.AWSUsageScrapeInterval)
		return val, nil
	}
}

func (u *UsageStore) kafkaCombinedMetrics(ctx context.Context, key, brokerID string, metricInput *cloudwatch.GetMetricStatisticsInput) func(string) (float64, error) {
	if _, ok := u.metricStore.Get(key); ok {
		return func(s string) (float64, error) {
			val, _ := u.metricStore.Get(s)
			return val, nil
		}
	}
	return func(s string) (float64, error) {
		var err error
		if brokerID == "" {
			keySplit := strings.Split(s, "-")
			brokerID = keySplit[len(keySplit)-1]
		}
		metricInput.Dimensions = append(metricInput.Dimensions, types.Dimension{
			Name: aws.String("Broker ID"), Value: aws.String(brokerID),
		})
		metricInput.MetricName = aws.String("CpuSystem")
		maxCpuSystem, err := u.getCWMetricsMax(ctx, metricInput)
		if err != nil {
			log.ErrorContext(ctx, "failed to scrape cloudwatch metrics", "error", err)
			return 0, err
		}
		metricInput.MetricName = aws.String("CpuUser")
		maxCpuUser, err := u.getCWMetricsMax(ctx, metricInput)
		if err != nil {
			log.ErrorContext(ctx, "failed to scrape cloudwatch metrics", "error", err)
			return 0, err
		}
		u.metricStore.Set(s, maxCpuSystem+maxCpuUser, u.config.AWSUsageScrapeInterval)
		return maxCpuSystem + maxCpuUser, nil
	}
}

func (u *UsageStore) GetMaxCPUUsage(ctx context.Context, key string, instance InstanceInfo) (float64, error) {
	metricInput := &cloudwatch.GetMetricStatisticsInput{
		Dimensions: []types.Dimension{{Name: aws.String(instance.IdentifierFieldName), Value: aws.String(instance.Identifier)}},
		EndTime:    aws.Time(instance.CollectedTime),
		StartTime:  aws.Time(instance.CollectedTime.Add(-u.config.AWSUsageTimeRange)),
		MetricName: aws.String("CPUUtilization"),
		Period:     aws.Int32(3600),
		Statistics: []types.Statistic{"Maximum"},
	}

	getMaxCPU := u.metricStoreGetter(ctx, key, metricInput)

	switch instance.Engine {
	case "postgres", "aurora-postgresql":
		metricInput.Namespace = aws.String("AWS/RDS")
	case "docdb":
		metricInput.Namespace = aws.String("AWS/DocDB")
	case "redis", "valkey":
		metricInput.Namespace = aws.String("AWS/ElastiCache")
	case "kafka":
		metricInput.Namespace = aws.String("AWS/Kafka")
		getMaxCPU = u.kafkaCombinedMetrics(ctx, key, instance.BrokerID, metricInput)
		if instance.Level == "Cluster" {
			var maxCPU, brokerMaxCPU float64
			var errs error
			var err error
			for i := 1; i <= instance.BrokerCount; i++ {
				brokerKey := fmt.Sprintf("%s-%d", instance.Identifier, i)
				getMaxCPU = u.kafkaCombinedMetrics(ctx, brokerKey, fmt.Sprint(i), metricInput)
				brokerMaxCPU, err = getMaxCPU(brokerKey)
				if err != nil {
					errs = errors.Join(errs, err)
					continue
				}
				maxCPU = math.Max(maxCPU, brokerMaxCPU)
			}
			return maxCPU, errs
		}
	default:
		metricInput.Namespace = aws.String("")
	}
	return getMaxCPU(key)
}

func (u *UsageStore) GetElastiCacheMaxMemoryUsage(ctx context.Context, key string, instance InstanceInfo) (float64, error) {
	metricInput := &cloudwatch.GetMetricStatisticsInput{
		Dimensions: []types.Dimension{{Name: aws.String(instance.IdentifierFieldName), Value: aws.String(instance.Identifier)}},
		EndTime:    aws.Time(instance.CollectedTime),
		StartTime:  aws.Time(instance.CollectedTime.Add(-u.config.AWSUsageTimeRange)),
		MetricName: aws.String("DatabaseMemoryUsagePercentage"),
		Period:     aws.Int32(3600),
		Statistics: []types.Statistic{"Maximum"},
	}
	getMaxMemory := u.metricStoreGetter(ctx, key, metricInput)
	return getMaxMemory(key)
}

func (u *UsageStore) getCWMetricsMax(ctx context.Context, metricInput *cloudwatch.GetMetricStatisticsInput) (float64, error) {
	var maxMetricValue float64
	output, err := u.cloudwatchClient.GetMetricStatistics(ctx, metricInput)
	if err != nil {
		return 0, err
	}
	for _, dataPoint := range output.Datapoints {
		maxMetricValue = math.Max(maxMetricValue, *dataPoint.Maximum)
	}
	return maxMetricValue, nil
}

func (u *UsageStore) scrapeRDSInfo(ctx context.Context) error {
	rdsInstances, err := u.rdsClient.DescribeDBInstances(ctx, &rds.DescribeDBInstancesInput{})
	collectedTime := time.Now().UTC()
	if err != nil {
		log.ErrorContext(ctx, "failed to scrape rds instances", "error", err)
		return err
	}
	for _, rdsInstance := range rdsInstances.DBInstances {
		instanceTags := make(map[string]string)
		for _, tag := range rdsInstance.TagList {
			instanceTags[*tag.Key] = *tag.Value
		}
		if isTagNotExist(u.AWSTags, instanceTags) {
			continue
		}
		u.infoStore.Set(*rdsInstance.DBInstanceIdentifier, InstanceInfo{
			Identifier:          aws.ToString(rdsInstance.DBInstanceIdentifier),
			InstanceClass:       aws.ToString(rdsInstance.DBInstanceClass),
			Engine:              aws.ToString(rdsInstance.Engine),
			Level:               "Instance",
			IdentifierFieldName: "DBInstanceIdentifier",
			CollectedTime:       collectedTime,
		}, u.config.AWSUsageScrapeInterval)
	}

	rdsClusters, err := u.rdsClient.DescribeDBClusters(ctx, &rds.DescribeDBClustersInput{})
	if err != nil {
		log.ErrorContext(ctx, "failed to scrape rds clusters", "error", err)
		return err
	}
	for _, cluster := range rdsClusters.DBClusters {
		instanceTags := make(map[string]string)
		for _, tag := range cluster.TagList {
			instanceTags[*tag.Key] = *tag.Value
		}
		if isTagNotExist(u.AWSTags, instanceTags) {
			continue
		}
		u.infoStore.Set(*cluster.DBClusterIdentifier, InstanceInfo{
			Identifier:          aws.ToString(cluster.DBClusterIdentifier),
			InstanceClass:       aws.ToString(cluster.DBClusterInstanceClass),
			Engine:              aws.ToString(cluster.Engine),
			Level:               "Cluster",
			IdentifierFieldName: "DBClusterIdentifier",
			CollectedTime:       collectedTime,
		}, u.config.AWSUsageScrapeInterval)
	}
	return nil
}

func (u *UsageStore) scrapeDocDBInfo(ctx context.Context) error {
	docDBInstances, err := u.docdbClient.DescribeDBInstances(ctx, &docdb.DescribeDBInstancesInput{})
	collectedTime := time.Now().UTC()
	if err != nil {
		log.ErrorContext(ctx, "failed to scrape docdb", "error", err)
		return err
	}
	for _, instance := range docDBInstances.DBInstances {
		if !strings.Contains(*instance.DBInstanceIdentifier, u.EnvironmentName) {
			continue
		}
		u.infoStore.Set(*instance.DBInstanceIdentifier, InstanceInfo{
			Identifier:          aws.ToString(instance.DBInstanceIdentifier),
			InstanceClass:       aws.ToString(instance.DBInstanceClass),
			Engine:              aws.ToString(instance.Engine),
			Level:               "Instance",
			IdentifierFieldName: "DBInstanceIdentifier",
			CollectedTime:       collectedTime,
		}, u.config.AWSUsageScrapeInterval)
	}

	docdbClusters, err := u.docdbClient.DescribeDBClusters(ctx, &docdb.DescribeDBClustersInput{})
	if err != nil {
		log.ErrorContext(ctx, "failed to scrape docdb clusters", "error", err)
		return err
	}
	for _, cluster := range docdbClusters.DBClusters {
		if !strings.Contains(*cluster.DBClusterIdentifier, u.EnvironmentName) {
			continue
		}
		u.infoStore.Set(*cluster.DBClusterIdentifier, InstanceInfo{
			Identifier:          aws.ToString(cluster.DBClusterIdentifier),
			InstanceClass:       "N/A", // Instance Class is not defined in DBCluster struct
			Engine:              aws.ToString(cluster.Engine),
			Level:               "Cluster",
			IdentifierFieldName: "DBClusterIdentifier",
			CollectedTime:       collectedTime,
		}, u.config.AWSUsageScrapeInterval)
	}
	return nil
}

func (u *UsageStore) scrapeElastiCacheInfo(ctx context.Context) error {
	redisCluster, err := u.elasticacheClient.DescribeCacheClusters(ctx, &elasticache.DescribeCacheClustersInput{})
	collectedTime := time.Now().UTC()
	if err != nil {
		log.ErrorContext(ctx, "failed to scrape elasticache clusters", "error", err)
		return err
	}
	for _, cluster := range redisCluster.CacheClusters {
		if !strings.Contains(*cluster.CacheClusterId, u.EnvironmentName) {
			continue
		}
		u.infoStore.Set(*cluster.CacheClusterId, InstanceInfo{
			Identifier:          aws.ToString(cluster.CacheClusterId),
			InstanceClass:       aws.ToString(cluster.CacheNodeType),
			Engine:              aws.ToString(cluster.Engine),
			Level:               "Cluster",
			IdentifierFieldName: "CacheClusterId",
			CollectedTime:       collectedTime,
		}, u.config.AWSUsageScrapeInterval)
	}
	return nil
}

func (u *UsageStore) scrapeKafkaInfo(ctx context.Context) error {
	kafkaClusters, err := u.kafkaClient.ListClustersV2(ctx, &kafka.ListClustersV2Input{})
	collectedTime := time.Now().UTC()
	if err != nil {
		log.ErrorContext(ctx, "failed to scrape kafka clusters", "error", err)
		return err
	}

	for _, kafkaCluster := range kafkaClusters.ClusterInfoList {
		if !strings.Contains(*kafkaCluster.ClusterName, u.EnvironmentName) {
			continue
		}
		u.infoStore.Set(*kafkaCluster.ClusterName, InstanceInfo{
			Identifier:          *kafkaCluster.ClusterName,
			InstanceClass:       *kafkaCluster.Provisioned.BrokerNodeGroupInfo.InstanceType,
			Engine:              "kafka",
			Level:               "Cluster",
			IdentifierFieldName: "Cluster Name",
			BrokerCount:         int(*kafkaCluster.Provisioned.NumberOfBrokerNodes),
			CollectedTime:       collectedTime,
		}, u.config.AWSUsageScrapeInterval)
		for i := 1; i <= int(*kafkaCluster.Provisioned.NumberOfBrokerNodes); i++ {
			u.infoStore.Set(fmt.Sprintf("%s-%d", *kafkaCluster.ClusterName, i), InstanceInfo{
				Identifier:          *kafkaCluster.ClusterName,
				InstanceClass:       *kafkaCluster.Provisioned.BrokerNodeGroupInfo.InstanceType,
				Engine:              "kafka",
				Level:               "Broker",
				IdentifierFieldName: "Cluster Name",
				BrokerID:            fmt.Sprint(i),
				BrokerCount:         int(*kafkaCluster.Provisioned.NumberOfBrokerNodes),
				CollectedTime:       collectedTime,
			}, u.config.AWSUsageScrapeInterval)
		}
	}
	return nil
}

func isTagNotExist(expectedTags, targetTags map[string]string) bool {
	tagsMatchMap := make(map[string]bool)
	for k, v := range expectedTags {
		for tk, tv := range targetTags {
			tagsMatched := k == tk && v == tv
			tagsMatchMap[k] = tagsMatched
			if tagsMatched {
				log.Debug("debug me this", k, v)
				break
			}
		}
	}
	isTagMismatch := false
	for _, v := range tagsMatchMap {
		isTagMismatch = isTagMismatch || !v
	}
	return isTagMismatch && len(tagsMatchMap) != 3
}
