package main

import (
	"flag"
	"fmt"
	"os"
)

// Declare a global variable to hold the kubeconfig path.
var kubeconfigPath string

func main() {
	// 1. Define CLI Flags
	environment := flag.String("environment", "", "The environment ID being validated (for logging)")
	blueprintName := flag.String("blueprint", "", "The name of the blueprint to validate against")
	// Add the new --kubeconfig flag.
	flag.StringVar(&kubeconfigPath, "kubeconfig", "", "Absolute path to the kubeconfig file (optional)")
	flag.Parse()

	if *environment == "" || *blueprintName == "" {
		fmt.Println("Error: --environment and --blueprint flags are required.")
		flag.Usage()
		os.Exit(2) // Exit with a different code for bad usage
	}

	fmt.Printf("🚀 Starting validation for environment '%s' against blueprint '%s'...\n", *environment, *blueprintName)

	// ... rest of the function remains the same ...
	// 2. Load Desired State
	blueprint, err := LoadBlueprint(*blueprintName)
	if err != nil {
		fmt.Printf("Error loading blueprint: %v\n", err)
		os.Exit(1)
	}

	// 3. Run Validation
	differences, err := RunValidation(blueprint)
	if err != nil {
		fmt.Printf("A fatal error occurred during validation: %v\n", err)
		os.Exit(1)
	}

	// 4. Report Results
	fmt.Println("---")
	if len(differences) == 0 {
		fmt.Println("✅ PASS: Actual state matches the blueprint.")
		os.Exit(0)
	} else {
		fmt.Printf("❌ FAIL: Found %d difference(s).\n", len(differences))
		for _, diff := range differences {
			fmt.Println(diff.String())
		}
		os.Exit(1)
	}
}