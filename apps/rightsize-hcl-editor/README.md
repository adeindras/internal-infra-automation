# Rightsize HCL Editor Tool (v2)

This is a generic, command-line Go program for reliably parsing and modifying HCL files. It operates using a simple `get/set/delete` model on data addresses, similar to how one might interact with a key-value store or a REST API.

The tool uses Go's native `github.com/hashicorp/hcl/v2/hclwrite` library, ensuring that all modifications preserve the original formatting, spacing, and comments of the HCL file.

## Prerequisites

* Go (version 1.18 or later) installed on your system.
* An HCL file (e.g., `terragrunt.hcl`).

## Compilation

To compile the tool, navigate to the directory containing `main.go` and run:

```plain
go build -o rightsize-rightsize-hcl-editor .
```

This will create an executable file named `rightsize-rightsize-hcl-editor` in the current directory.

## Usage

The tool has three primary commands: `get`, `set`, and `delete`. It operates on HCL "addresses"—dot-separated paths to the desired attribute.

1. `get`\
   Reads and prints the value of an attribute at a given address.\
   **Command:**

   ```plain

   ./rightsize-rightsize-hcl-editor get -file <filename> -address <hcl.address>
   ```

   **Example:**\
   To get the value of the `name` attribute inside the `one` block:

   ```plain
   ./rightsize-hcl-editor get -file config.hcl -address inputs.instances.one.name
   ```

    **Output:**

    ```plain
    "one"
    ```

2. `set`\
   Sets (creates or updates) the value of an attribute at a given address.

   **Command:**

   ```plain
   ./rightsize-hcl-editor set -file <filename> -address <hcl.address> -value <hcl_value>
   ```

   **Value Formatting:** The value must be a valid HCL expression string.
   * For strings, wrap in double quotes: `'"my-string"'`
   * For numbers: `'123'`
   * For booleans: `'true'`
   * For objects/maps, use HCL syntax: `'{ name = "three", instance = "db.t4g.large" }'`

   **Example 1: Modify a simple attribute**\
   To change the `instance` attribute of the `one` block:

   ```plain
   ./rightsize-hcl-editor set -file config.hcl -address inputs.instances.one.instance -value '"db.t3.large"'
   ```

    **Example 2: Add a new, complex block**\
    To add a new block named `three`:

    ```plain
    ./rightsize-hcl-editor set -file config.hcl -address inputs.instances.three -value '{ name = "three", instance = "db.t4g.large" }'
    ```

    This command works for both creating new attributes and updating existing ones.

3. `delete`\
   Removes an attribute or block at a given address.
   **Command:**

    ```plain
   ./rightsize-hcl-editor delete -file <filename> -address <hcl.address>
   ```

   **Example:**\
   To remove the entire `two` block from `inputs.instances`:

   ```plain
   ./rightsize-hcl-editor delete -file config.hcl -address inputs.instances.two
    ```
