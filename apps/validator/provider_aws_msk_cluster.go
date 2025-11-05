package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/kafka"
)

// AWSMSKProvider validates AWS MSK (Managed Streaming for Kafka) clusters.
type AWSMSKProvider struct{}

func init() {
	// Register this provider with its type string.
	providerRegistry["aws-msk-cluster"] = &AWSMSKProvider{}
}

func (p *AWSMSKProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}
	client := kafka.NewFromConfig(cfg)

	// Step 1: Find the cluster ARN using the cluster name from the blueprint.
	listInput := &kafka.ListClustersV2Input{
		ClusterNameFilter: &res.Name,
	}
	listOutput, err := client.ListClustersV2(context.TODO(), listInput)
	if err != nil {
		return nil, fmt.Errorf("failed to list MSK clusters with name %s: %w", res.Name, err)
	}
	if len(listOutput.ClusterInfoList) == 0 {
		return nil, fmt.Errorf("no MSK cluster found with name: %s", res.Name)
	}
	if len(listOutput.ClusterInfoList) > 1 {
		return nil, fmt.Errorf("multiple MSK clusters found with name '%s', cannot proceed", res.Name)
	}
	clusterArn := *listOutput.ClusterInfoList[0].ClusterArn

	// Step 2: Describe the cluster using the ARN found in the previous step.
	describeInput := &kafka.DescribeClusterInput{
		ClusterArn: &clusterArn,
	}
	describeOutput, err := client.DescribeCluster(context.TODO(), describeInput)
	if err != nil {
		return nil, fmt.Errorf("failed to describe MSK cluster %s: %w", clusterArn, err)
	}

	if describeOutput.ClusterInfo == nil || describeOutput.ClusterInfo.BrokerNodeGroupInfo == nil {
		return nil, fmt.Errorf("could not retrieve broker node info for MSK cluster %s", clusterArn)
	}

	// Get the expected instance type from the blueprint spec.
	expectedType, ok := res.Spec["instanceType"].(string)
	if !ok {
		// If instanceType is not specified in the blueprint, skip validation.
		return diffs, nil
	}

	actualType := *describeOutput.ClusterInfo.BrokerNodeGroupInfo.InstanceType
	if actualType != expectedType {
		diffs = append(diffs, Difference{
			ResourceName: res.Name, // Report using the friendly name from the blueprint
			Provider:     "aws-msk-cluster",
			Attribute:    "Broker Instance Type",
			Expected:     expectedType,
			Actual:       actualType,
		})
	}

	return diffs, nil
}
