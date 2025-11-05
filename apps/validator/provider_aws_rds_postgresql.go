package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/rds"
)

// AWSRDSPostgreSQLProvider validates AWS RDS instances.
type AWSRDSPostgreSQLProvider struct{}

func init() {
	providerRegistry["aws-rds-postgresql"] = &AWSRDSPostgreSQLProvider{}
}

func (p *AWSRDSPostgreSQLProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}
	client := rds.NewFromConfig(cfg)

	input := &rds.DescribeDBInstancesInput{
		DBInstanceIdentifier: &res.Name,
	}

	output, err := client.DescribeDBInstances(context.TODO(), input)
	if err != nil {
		return nil, fmt.Errorf("failed to describe RDS instance %s: %w", res.Name, err)
	}

	if len(output.DBInstances) == 0 {
		return nil, fmt.Errorf("RDS instance %s not found", res.Name)
	}
	instance := output.DBInstances[0]

	// Validate Instance Class
	expectedClass, ok := res.Spec["instanceClass"].(string)
	if ok && *instance.DBInstanceClass != expectedClass {
		diffs = append(diffs, Difference{
			ResourceName: res.Name,
			Provider:     "aws-rds-postgresql-postgresql",
			Attribute:    "Instance Class",
			Expected:     expectedClass,
			Actual:       *instance.DBInstanceClass,
		})
	}

	return diffs, nil
}
