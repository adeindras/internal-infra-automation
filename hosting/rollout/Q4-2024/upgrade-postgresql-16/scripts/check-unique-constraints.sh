#!/bin/bash

# PostgreSQL Unique Constraint and Index Violation Checker

# Configuration - Please modify these variables
DB_NAME="$DB_NAME"
OUTPUT_DIR="."

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

# Function to check unique constraints and indexes
check_unique_violations() {
    local schema_name="$1"
    local table_name="$2"
    local constraint_name="$3"
    local constraint_columns="$4"

    echo "Checking unique constraint/index violations for $schema_name.$table_name ($constraint_name)"

    # Generate a query to find duplicate entries
    local duplicate_query="
WITH duplicates AS (
    SELECT $constraint_columns, COUNT(*) as duplicate_count
    FROM $schema_name.$table_name
    GROUP BY $constraint_columns
    HAVING COUNT(*) > 1
)
SELECT 
    d.*,
    (
        SELECT json_agg(t.*)
        FROM $schema_name.$table_name t
        WHERE $(echo "$constraint_columns" | awk -F, '{
            for(i=1;i<=NF;i++) {
                printf "(t.%s = d.%s)", $i, $i
                if(i<NF) printf " AND "
            }
        }')
    ) as duplicate_rows
FROM duplicates d;
"
    echo "${duplicate_query}"

    # Execute the query and save results
    psql -d "$DB_NAME" -c "$duplicate_query" -t -A -F',' > "$OUTPUT_DIR/${schema_name}_${table_name}_${constraint_name}_violations.csv"
}

# Main script
main() {
    # Retrieve all unique constraints and indexes
    local unique_constraints=$(psql -d "$DB_NAME" -t -A -c "
SELECT 
    n.nspname AS schema_name,
    t.relname AS table_name,
    c.conname AS constraint_name,
    string_agg(a.attname, ', ' ORDER BY c.conkey[i]) AS constraint_columns
FROM 
    pg_constraint c
JOIN 
    pg_class t ON c.conrelid = t.oid
JOIN 
    pg_namespace n ON t.relnamespace = n.oid
CROSS JOIN 
    generate_series(1, array_length(c.conkey, 1)) AS i
JOIN 
    pg_attribute a ON a.attnum = c.conkey[i] AND a.attrelid = t.oid
WHERE 
    c.contype IN ('u', 'x','p')  -- unique constraint or index
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
GROUP BY 
    n.nspname, t.relname, c.conname
ORDER BY 
    schema_name, table_name;
")

    if [ -z "$unique_constraints" ]; then
        echo "No unique constraints, unique indexes, or primary keys found in the database."
        exit 0
    fi

    # Process each unique constraint/index
    echo "$unique_constraints" | while IFS='|' read -r schema table constraint columns; do
        check_unique_violations "$schema" "$table" "$constraint" "$columns"
    done

    # Generate summary report
    echo "Generating summary report..."
    for file in "$OUTPUT_DIR"/*_violations.csv; do
        if [ -s "$file" ]; then
            echo "Violations found in $file"
            head -n 5 "$file"  # Show first 5 lines of violations
            echo "Total violations: $(wc -l < "$file")"
        fi
    done
}

# Error handling
set -e
trap 'echo "Error: $BASH_COMMAND failed with error code $?"' ERR

# Run the script
main

echo "Unique constraint violation check completed. Check output files in $OUTPUT_DIR"