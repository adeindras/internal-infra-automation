package main

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Blueprint defines the overall structure of a blueprint file
type Blueprint struct {
	Resources []Resource `yaml:"resources"`
}

// Resource defines a single resource to be validated.
type Resource struct {
	Name      string                 `yaml:"name"`
	Type      string                 `yaml:"type"`
	Namespace string                 `yaml:"namespace,omitempty"`
	Spec      map[string]interface{} `yaml:"spec"`
}

// LoadBlueprint reads and parses a blueprint file from the ./blueprints directory
func LoadBlueprint(name string) (*Blueprint, error) {
	path := filepath.Join("blueprints", name+".yaml")
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("could not read blueprint file %s: %w", path, err)
	}

	var bp Blueprint
	if err := yaml.Unmarshal(data, &bp); err != nil {
		return nil, fmt.Errorf("could not parse blueprint YAML %s: %w", path, err)
	}

	return &bp, nil
}
