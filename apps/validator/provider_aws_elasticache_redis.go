package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/elasticache"
)

// AWSElastiCacheRedisProvider validates AWS ElastiCache for Redis clusters.
type AWSElastiCacheRedisProvider struct{}

func init() {
	// Register this provider with its type string.
	providerRegistry["aws-elasticache-redis"] = &AWSElastiCacheRedisProvider{}
}

func (p *AWSElastiCacheRedisProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}
	client := elasticache.NewFromConfig(cfg)

	// 1. Describe the Replication Group to get its member clusters.
	rgInput := &elasticache.DescribeReplicationGroupsInput{
		ReplicationGroupId: &res.Name,
	}
	rgOutput, err := client.DescribeReplicationGroups(context.TODO(), rgInput)
	if err != nil {
		return nil, fmt.Errorf("failed to describe ElastiCache replication group %s: %w", res.Name, err)
	}
	if len(rgOutput.ReplicationGroups) == 0 {
		return nil, fmt.Errorf("ElastiCache replication group %s not found", res.Name)
	}
	memberClusters := rgOutput.ReplicationGroups[0].MemberClusters

	// Get the expected node type from the blueprint.
	expectedType, ok := res.Spec["cacheNodeType"].(string)
	if !ok {
		// If cacheNodeType is not specified, skip validation.
		return diffs, nil
	}

	// 2. Loop through each member and describe it to check its node type.
	for _, memberClusterId := range memberClusters {
		ccInput := &elasticache.DescribeCacheClustersInput{
			CacheClusterId: &memberClusterId,
		}
		ccOutput, err := client.DescribeCacheClusters(context.TODO(), ccInput)
		if err != nil {
			return nil, fmt.Errorf("failed to describe member cache cluster %s: %w", memberClusterId, err)
		}
		if len(ccOutput.CacheClusters) == 0 {
			// This case is unlikely if the parent RG exists, but good practice.
			return nil, fmt.Errorf("member cache cluster %s not found", memberClusterId)
		}
		cluster := ccOutput.CacheClusters[0]

		if *cluster.CacheNodeType != expectedType {
			diffs = append(diffs, Difference{
				ResourceName: memberClusterId, // Report the specific member cluster that drifted.
				Provider:     "aws-elasticache-redis",
				Attribute:    "Cache Node Type",
				Expected:     expectedType,
				Actual:       *cluster.CacheNodeType,
			})
		}
	}

	return diffs, nil
}
