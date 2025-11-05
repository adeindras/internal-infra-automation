package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/docdbelastic"
)

// AWSDocDBElasticProvider validates AWS DocumentDB Elastic Clusters.
type AWSDocDBElasticProvider struct{}

func init() {
	// Register this provider with its type string.
	providerRegistry["aws-docdb-elastic"] = &AWSDocDBElasticProvider{}
}

func (p *AWSDocDBElasticProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}
	client := docdbelastic.NewFromConfig(cfg)

	// Step 1: Find the cluster ARN from the cluster name by listing all clusters.
	var clusterArn string
	paginator := docdbelastic.NewListClustersPaginator(client, &docdbelastic.ListClustersInput{})

	for paginator.HasMorePages() {
		page, err := paginator.NextPage(context.TODO())
		if err != nil {
			return nil, fmt.Errorf("failed to list DocDB Elastic clusters: %w", err)
		}
		for _, clusterSummary := range page.Clusters {
			if clusterSummary.ClusterName != nil && *clusterSummary.ClusterName == res.Name {
				clusterArn = *clusterSummary.ClusterArn
				break // Found the cluster, exit loop.
			}
		}
		if clusterArn != "" {
			break
		}
	}

	if clusterArn == "" {
		return nil, fmt.Errorf("no DocDB Elastic cluster found with name: %s", res.Name)
	}

	// Step 2: Use the found ARN to get detailed cluster info.
	input := &docdbelastic.GetClusterInput{
		ClusterArn: &clusterArn,
	}
	output, err := client.GetCluster(context.TODO(), input)
	if err != nil {
		return nil, fmt.Errorf("failed to get DocDB Elastic cluster %s: %w", res.Name, err)
	}
	if output.Cluster == nil {
		return nil, fmt.Errorf("cluster info not found for DocDB Elastic cluster %s", res.Name)
	}
	cluster := output.Cluster

	// Validate Shard Count
	diffs = append(diffs, checkInt32Spec(res, "shardCount", cluster.ShardCount)...)
	// Validate Shard Instance Count
	diffs = append(diffs, checkInt32Spec(res, "shardInstanceCount", cluster.ShardInstanceCount)...)
	// Validate Shard Capacity
	diffs = append(diffs, checkInt32Spec(res, "shardCapacity", cluster.ShardCapacity)...)

	return diffs, nil
}

// checkInt32Spec is a helper to compare an expected int value from the blueprint
// with an actual *int32 value from the AWS SDK.
func checkInt32Spec(res Resource, key string, actual *int32) []Difference {
	var diffs []Difference
	if expected, ok := res.Spec[key].(int); ok {
		if actual == nil || *actual != int32(expected) {
			actualVal := "Not Set"
			if actual != nil {
				actualVal = fmt.Sprintf("%d", *actual)
			}
			diffs = append(diffs, Difference{
				ResourceName: res.Name,
				Provider:     "aws-docdb-elastic",
				Attribute:    key,
				Expected:     expected,
				Actual:       actualVal,
			})
		}
	}
	return diffs
}
