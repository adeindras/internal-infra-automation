package main

import "fmt"

// RunValidation orchestrates the validation process for an entire blueprint.
func RunValidation(bp *Blueprint) ([]Difference, error) {
	var allDiffs []Difference

	for _, resource := range bp.Resources {
		fmt.Printf("🔍 Validating resource: %s (%s)\n", resource.Name, resource.Type)
		provider, exists := providerRegistry[resource.Type]
		if !exists {
			return nil, fmt.Errorf("no provider found for resource type: %s", resource.Type)
		}

		diffs, err := provider.Validate(resource)
		if err != nil {
			fmt.Printf("❌ Error validating %s: %v\n", resource.Name, err)
			// For MVP, we stop on error, but could be made more resilient.
			return nil, err
		}
		allDiffs = append(allDiffs, diffs...)
	}

	return allDiffs, nil
}
