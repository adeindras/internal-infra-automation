package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/rds"
	"github.com/aws/aws-sdk-go-v2/service/rds/types"
)

// AWSRDSAuroraProvisionedProvider validates AWS RDS Aurora Provisioned clusters.
type AWSRDSAuroraProvisionedProvider struct{}

func init() {
	// Register this provider with its type string.
	providerRegistry["aws-rds-aurora-provisioned"] = &AWSRDSAuroraProvisionedProvider{}
}

func (p *AWSRDSAuroraProvisionedProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}
	client := rds.NewFromConfig(cfg)

	// Step 1: Describe the cluster to identify the writer and reader roles.
	clusterInput := &rds.DescribeDBClustersInput{
		DBClusterIdentifier: &res.Name,
	}
	clusterOutput, err := client.DescribeDBClusters(context.TODO(), clusterInput)
	if err != nil {
		return nil, fmt.Errorf("failed to describe DB cluster %s: %w", res.Name, err)
	}
	if len(clusterOutput.DBClusters) == 0 {
		return nil, fmt.Errorf("DB cluster %s not found", res.Name)
	}

	// Create a map of instance identifiers to their writer status.
	instanceRoles := make(map[string]bool) // map[instanceID]isWriter
	for _, member := range clusterOutput.DBClusters[0].DBClusterMembers {
		if member.DBInstanceIdentifier != nil {
			instanceRoles[*member.DBInstanceIdentifier] = *member.IsClusterWriter
		}
	}

	// Step 2: Describe all DB instances in the cluster to get their instance class.
	instancesInput := &rds.DescribeDBInstancesInput{
		Filters: []types.Filter{
			{
				Name:   aws.String("db-cluster-id"),
				Values: []string{res.Name},
			},
		},
	}
	instancesOutput, err := client.DescribeDBInstances(context.TODO(), instancesInput)
	if err != nil {
		return nil, fmt.Errorf("failed to describe DB instances for cluster %s: %w", res.Name, err)
	}

	// Get expected instance classes from the blueprint spec.
	expectedWriterClass, writerOk := res.Spec["instanceClass"].(string)
	expectedReaderClass, readerOk := res.Spec["instanceClass"].(string)

	// Step 3: Loop through instances and validate using the roles map.
	for _, instance := range instancesOutput.DBInstances {
		if instance.DBInstanceIdentifier == nil || instance.DBInstanceClass == nil {
			continue // Skip instances with missing data.
		}
		instanceID := *instance.DBInstanceIdentifier
		actualClass := *instance.DBInstanceClass
		isWriter := instanceRoles[instanceID]

		// Check the writer instance type.
		if isWriter && writerOk {
			if actualClass != expectedWriterClass {
				diffs = append(diffs, Difference{
					ResourceName: instanceID,
					Provider:     "aws-rds-aurora-provisioned",
					Attribute:    "Writer Instance Class",
					Expected:     expectedWriterClass,
					Actual:       actualClass,
				})
			}
		}

		// Check the reader instance types.
		if !isWriter && readerOk {
			if actualClass != expectedReaderClass {
				diffs = append(diffs, Difference{
					ResourceName: instanceID,
					Provider:     "aws-rds-aurora-provisioned",
					Attribute:    "Reader Instance Class",
					Expected:     expectedReaderClass,
					Actual:       actualClass,
				})
			}
		}
	}

	return diffs, nil
}
