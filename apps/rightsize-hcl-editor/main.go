// Copyright (c) 2017-2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.

// initial author		: Muhammad Rizky Amrullah, helped by Gemini 2.5 Pro
// initial author email	: rizky.amrullah@accelbyte.net

package main

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/hashicorp/hcl/v2"
	"github.com/hashicorp/hcl/v2/hclsyntax"
	"github.com/hashicorp/hcl/v2/hclwrite"
)

// main function serves as the command-line interface router.
func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	var filename, address, value string
	parseFlags(os.Args[2:], &filename, &address, &value)

	if filename == "" || address == "" {
		fmt.Println("Error: -file and -address flags are required.")
		printUsage()
		os.Exit(1)
	}

	switch os.Args[1] {
	case "get":
		handleGet(filename, address)
	case "set":
		if value == "" {
			fmt.Println("Error: -value flag is required for 'set' command.")
			printUsage()
			os.Exit(1)
		}
		handleSet(filename, address, value)
	case "delete":
		handleDelete(filename, address)
	default:
		fmt.Printf("Unknown command: %s\n", os.Args[1])
		printUsage()
		os.Exit(1)
	}
}

// handleGet finds and prints a value or block from the HCL file.
func handleGet(filename, address string) {
	file, _ := loadHCLFile(filename)
	body := file.Body()

	parentBody, leafName, err := findParentBodyForAttribute(body, address)
	if err != nil {
		fmt.Printf("Error: %v\n", err)
		os.Exit(1)
	}

	// First, check if the target is an attribute.
	attr := parentBody.GetAttribute(leafName)
	if attr != nil {
		// It's an attribute, print its value.
		fmt.Println(strings.TrimSpace(string(attr.Expr().BuildTokens(nil).Bytes())))
		return
	}

	// If not an attribute, check if it's a block.
	block := parentBody.FirstMatchingBlock(leafName, []string{})
	if block != nil {
		// It's a block, print its entire definition.
		fmt.Println(strings.TrimSpace(string(block.BuildTokens(nil).Bytes())))
		return
	}

	// If it's neither, then it's not found.
	fmt.Printf("Error: attribute or block '%s' not found at the specified address.\n", address)
	os.Exit(1)
}

// handleSet creates or updates a value in the HCL file.
// It intelligently handles creating blocks vs. attributes based on the input value.
func handleSet(filename, address, value string) {
	file, _ := loadHCLFile(filename)
	body := file.Body()

	parentBody, leafName, err := findParentBodyForAttribute(body, address)
	if err != nil {
		// If the parent block doesn't exist, we need to create it.
		parentBody, err = createParentPath(body, address)
		if err != nil {
			fmt.Printf("Error creating path: %v\n", err)
			os.Exit(1)
		}
		parts := strings.Split(address, ".")
		leafName = parts[len(parts)-1]
	}

	trimmedValue := strings.TrimSpace(value)
	// If the value is an object, treat it as a request to create a block.
	if strings.HasPrefix(trimmedValue, "{") && strings.HasSuffix(trimmedValue, "}") {
		// Remove any existing attribute with the same name to avoid conflicts.
		parentBody.RemoveAttribute(leafName)

		// Find an existing block or create a new one.
		block := parentBody.FirstMatchingBlock(leafName, []string{})
		if block == nil {
			block = parentBody.AppendNewBlock(leafName, []string{})
		}
		// Clear any existing content in the block to ensure a clean write.
		block.Body().Clear()

		// Parse the entire value string as an HCL expression.
		expr, diags := hclsyntax.ParseExpression([]byte(trimmedValue), "value.hcl", hcl.InitialPos)
		if diags.HasErrors() {
			fmt.Printf("Error: invalid HCL object expression in value.\n%v\n", diags)
			os.Exit(1)
		}

		// We expect the expression to be an object constructor.
		objExpr, ok := expr.(*hclsyntax.ObjectConsExpr)
		if !ok {
			fmt.Println("Error: value for block creation must be a valid HCL object literal.")
			os.Exit(1)
		}

		// Iterate over the key/value pairs in the parsed object expression
		// and copy them into the new block without evaluating them.
		for _, item := range objExpr.Items {
			// Get the key as a string. This assumes simple string keys.
			key := hcl.ExprAsKeyword(item.KeyExpr)
			if key == "" {
				fmt.Printf("Error: invalid key in value object: %s\n", item.KeyExpr.Range().String())
				os.Exit(1)
			}
			// Get the raw source bytes of the value expression to preserve it.
			// The range is relative to the buffer we parsed, which is trimmedValue.
			valueExprRange := item.ValueExpr.Range()
			valueExprBytes := []byte(trimmedValue)[valueExprRange.Start.Byte:valueExprRange.End.Byte]

			// To create hclwrite.Tokens, we lex the raw bytes of the expression.
			hclsyntaxTokens, diags := hclsyntax.LexExpression(valueExprBytes, "value-expr", hcl.InitialPos)
			if diags.HasErrors() {
				fmt.Printf("Error re-lexing value expression '%s': %v\n", string(valueExprBytes), diags)
				os.Exit(1)
			}

			// Manually convert the syntax tokens to write tokens.
			valTokens := make(hclwrite.Tokens, len(hclsyntaxTokens))
			for i, t := range hclsyntaxTokens {
				valTokens[i] = &hclwrite.Token{
					Type:  t.Type,
					Bytes: t.Bytes,
				}
			}

			block.Body().SetAttributeRaw(key, valTokens)
		}

		saveHCLFile(filename, file)
		fmt.Printf("Successfully set block '%s' in %s\n", address, filename)
		return
	}

	// If we're here, it's a primitive attribute. Use the existing logic.
	valBytes := []byte(value)
	hclsyntaxTokens, diags := hclsyntax.LexExpression(valBytes, "value", hcl.InitialPos)
	if diags.HasErrors() {
		fmt.Printf("Error: invalid HCL value syntax provided.\n%v\n", diags)
		os.Exit(1)
	}

	hclwriteTokens := make(hclwrite.Tokens, len(hclsyntaxTokens))
	for i, t := range hclsyntaxTokens {
		hclwriteTokens[i] = &hclwrite.Token{
			Type:  t.Type,
			Bytes: t.Bytes,
		}
	}

	parentBody.SetAttributeRaw(leafName, hclwriteTokens)
	saveHCLFile(filename, file)
	fmt.Printf("Successfully set attribute '%s' in %s\n", address, filename)
}

// handleDelete removes a value from the HCL file.
func handleDelete(filename, address string) {
	file, _ := loadHCLFile(filename)
	body := file.Body()

	parentBody, leafName, err := findParentBodyForAttribute(body, address)
	if err != nil {
		fmt.Printf("Error: %v\n", err)
		os.Exit(1)
	}

	// Check if the target is an attribute or a block
	if attr := parentBody.GetAttribute(leafName); attr != nil {
		parentBody.RemoveAttribute(leafName)
	} else if block := parentBody.FirstMatchingBlock(leafName, []string{}); block != nil {
		parentBody.RemoveBlock(block)
	} else {
		fmt.Printf("Error: attribute or block '%s' not found for deletion.\n", address)
		os.Exit(1)
	}

	saveHCLFile(filename, file)
	fmt.Printf("Successfully deleted '%s' from %s\n", address, filename)
}

// --- Helper Functions ---

// findParentBodyForAttribute navigates the HCL block structure to find the direct parent body of a target address.
func findParentBodyForAttribute(body *hclwrite.Body, address string) (*hclwrite.Body, string, error) {
	parts := strings.Split(address, ".")
	if len(parts) == 0 {
		return nil, "", errors.New("address cannot be empty")
	}

	currentBody := body
	// Navigate through all parts of the address except for the last one (the leaf).
	for i := 0; i < len(parts)-1; i++ {
		part := parts[i]
		// This logic expects a block with a type name but no labels.
		block := currentBody.FirstMatchingBlock(part, []string{})
		if block == nil {
			return nil, "", fmt.Errorf("address part (block) '%s' not found in '%s'", part, strings.Join(parts[:i+1], "."))
		}
		currentBody = block.Body()
	}

	leafName := parts[len(parts)-1]
	return currentBody, leafName, nil
}

// createParentPath creates nested blocks if they don't exist.
func createParentPath(body *hclwrite.Body, address string) (*hclwrite.Body, error) {
	parts := strings.Split(address, ".")
	currentBody := body
	// Iterate to create parent blocks, stopping before the final leaf part.
	for i := 0; i < len(parts)-1; i++ {
		part := parts[i]
		block := currentBody.FirstMatchingBlock(part, []string{})
		if block == nil {
			block = currentBody.AppendNewBlock(part, []string{})
		}
		currentBody = block.Body()
	}
	return currentBody, nil
}

// loadHCLFile reads and parses an HCL file.
func loadHCLFile(filename string) (*hclwrite.File, *hclwrite.Body) {
	src, err := os.ReadFile(filename)
	if err != nil {
		fmt.Printf("Error reading file %s: %v\n", filename, err)
		os.Exit(1)
	}
	file, diags := hclwrite.ParseConfig(src, filename, hcl.InitialPos)
	if diags.HasErrors() {
		fmt.Printf("Error parsing HCL file %s: %v\n", filename, diags)
		os.Exit(1)
	}
	return file, file.Body()
}

// saveHCLFile writes the modified HCL content back to the file.
func saveHCLFile(filename string, file *hclwrite.File) {
	// Run the formatter on the file's bytes to fix any indentation
	// issues introduced during the modification. This is the canonical
	// way to ensure clean output.
	formattedBytes := hclwrite.Format(file.Bytes())
	err := os.WriteFile(filename, formattedBytes, 0644)
	if err != nil {
		fmt.Printf("Error writing to file %s: %v\n", filename, err)
		os.Exit(1)
	}
}

// A simple manual flag parser
func parseFlags(args []string, filename, address, value *string) {
	for i := 0; i < len(args); i += 2 {
		if i+1 >= len(args) {
			break
		}
		flag := args[i]
		val := args[i+1]
		switch flag {
		case "-file":
			*filename = val
		case "-address":
			*address = val
		case "-value":
			*value = val
		}
	}
}

// printUsage prints the tool's usage instructions.
func printUsage() {
	fmt.Println("Rightsize HCL Editor: A tool to get, set, or delete HCL attributes inside blocks.")
	fmt.Println("\nUsage: rightsize-hcl-editor <command> -file <path> -address <addr> [-value <val>]")
	fmt.Println("\nAuthor: Muhammad Rizky Amrullah, Rightsize Team (rizky.amrullah@accelbyte.net)")
	fmt.Println("\nCommands:")
	fmt.Println("  get    - Reads an attribute or a block at the address.")
	fmt.Println("  set    - Creates or updates an attribute or a block at the address.")
	fmt.Println("  delete - Removes an attribute or a whole block at the address.")
	fmt.Println("\nRequired Flags:")
	fmt.Println("  -file <path>      : Path to the HCL file.")
	fmt.Println("  -address <string> : Dot-separated HCL address (e.g., inputs.instances.one.name).")
	fmt.Println("\nFlag for 'set':")
	fmt.Println("  -value <string>   : HCL value to set. Use '{...}' for blocks (e.g., '{ name = \"foo\" }').")
}
