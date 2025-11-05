package main

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/opensearch"
)

// AWSOpenSearchDomainProvider validates AWS OpenSearch domains.
type AWSOpenSearchDomainProvider struct{}

func init() {
	// Register this provider with its type string.
	providerRegistry["aws-opensearch-domain"] = &AWSOpenSearchDomainProvider{}
}

func (p *AWSOpenSearchDomainProvider) Validate(res Resource) ([]Difference, error) {
	var diffs []Difference

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}

	client := opensearch.NewFromConfig(cfg)

	input := &opensearch.DescribeDomainInput{
		DomainName: &res.Name,
	}

	output, err := client.DescribeDomain(context.TODO(), input)
	if err != nil {
		return nil, fmt.Errorf("failed to describe OpenSearch domain %s: %w", res.Name, err)
	}

	if output.DomainStatus == nil || output.DomainStatus.ClusterConfig == nil {
		return nil, fmt.Errorf("could not retrieve cluster config for OpenSearch domain %s", res.Name)
	}

	// Get the expected instance type from the blueprint spec.
	expectedType, ok := res.Spec["instanceType"].(string)
	if !ok {
		// If instanceType is not specified in the blueprint, skip validation.
		return diffs, nil
	}

	// The SDK returns an enum type, so we convert it to a string for comparison.
	actualType := string(output.DomainStatus.ClusterConfig.InstanceType)

	if actualType != expectedType {
		diffs = append(diffs, Difference{
			ResourceName: res.Name,
			Provider:     "aws-opensearch-domain",
			Attribute:    "Instance Type",
			Expected:     expectedType,
			Actual:       actualType,
		})
	}

	return diffs, nil
}
