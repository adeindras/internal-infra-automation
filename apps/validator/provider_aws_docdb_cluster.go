package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/docdb"
	"github.com/aws/aws-sdk-go-v2/service/docdb/types"
)

// AWSDocDBClusterProvider validates AWS RDS instances.
type AWSDocDBClusterProvider struct{}

func init() {
	providerRegistry["aws-docdb-cluster"] = &AWSDocDBClusterProvider{}
}

func (p *AWSDocDBClusterProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}
	client := docdb.NewFromConfig(cfg)

	// To get the instance class, we must describe the instances within the cluster.
	input := &docdb.DescribeDBInstancesInput{
		Filters: []types.Filter{
			{
				Name:   aws.String("db-cluster-id"),
				Values: []string{res.Name},
			},
		},
	}

	output, err := client.DescribeDBInstances(context.TODO(), input)
	if err != nil {
		return nil, fmt.Errorf("failed to describe instances for DocDB cluster %s: %w", res.Name, err)
	}

	if len(output.DBInstances) == 0 {
		return nil, fmt.Errorf("no instances found for DocDB cluster %s", res.Name)
	}

	// Get the expected instance class from the blueprint spec.
	expectedClass, ok := res.Spec["instanceClass"].(string)
	if !ok {
		// If instanceClass is not specified in the blueprint, skip validation.
		return diffs, nil
	}

	// Check that all instances in the cluster match the expected class.
	for _, instance := range output.DBInstances {
		if *instance.DBInstanceClass != expectedClass {
			diffs = append(diffs, Difference{
				// Report the specific instance that has drifted.
				ResourceName: *instance.DBInstanceIdentifier,
				Provider:     "aws-docdb-cluster",
				Attribute:    "Instance Class",
				Expected:     expectedClass,
				Actual:       *instance.DBInstanceClass,
			})
		}
	}

	return diffs, nil
}
