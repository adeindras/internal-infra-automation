package main

import "fmt"

// Difference represents a single deviation from the blueprint.
type Difference struct {
	ResourceName string
	Provider     string
	Attribute    string
	Expected     interface{}
	Actual       interface{}
}

func (d Difference) String() string {
	return fmt.Sprintf(
		"  - Resource: %s\n    Provider: %s\n    Attribute: %s\n    Expected: %v\n    Actual: %v",
		d.ResourceName,
		d.Provider,
		d.Attribute,
		d.Expected,
		d.Actual,
	)
}

// Provider is the interface that all resource providers must implement.
type Provider interface {
	Validate(res Resource) ([]Difference, error)
}

// providerRegistry holds all registered provider implementations.
var providerRegistry = make(map[string]Provider)
