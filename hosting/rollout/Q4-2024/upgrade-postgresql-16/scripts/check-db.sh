#!/bin/bash


# Exit on any error
set -e

#only useful in my pipeline
#I set PGHOST, PGUSER, PGPASSWORD in pgpass file
source pgpass


# Color codes
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Check if environment variables are set
if [ -z "$PGHOST" ] || [ -z "$PGUSER" ] || [ -z "$PGPASSWORD" ]; then
    echo -e "${RED}Error: Required environment variables PGHOST, PGUSER, and PGPASSWORD must be set${NC}"
    exit 1
fi

# Function to format size in human-readable format
format_size() {
    local size=$1
    awk 'BEGIN {
        suffix[0] = "B"; suffix[1] = "KB"; suffix[2] = "MB";
        suffix[3] = "GB"; suffix[4] = "TB";
        for(i=4;$1>1024 && i>0;i--) $1/=1024;
        printf("%.2f %s\n", $1, suffix[i]);
    }' <<< "$size"
}

# Function to execute PostgreSQL queries safely
execute_query() {
    PGPASSWORD=$PGPASSWORD psql -h "$PGHOST" -U "$PGUSER" "$@" 2>&1 || {
        echo -e "${RED}Error executing query: $*${NC}" >&2
        return 1
    }
}

echo -e "\n${BOLD}${BLUE}=== PostgreSQL Size Report ===${NC}"
echo -e "${CYAN}Host: $PGHOST${NC}"
echo -e "${CYAN}User: $PGUSER${NC}"
echo -e "${CYAN}Generated at: $(date)${NC}"
echo -e "${BOLD}${BLUE}===============================${NC}\n"

# Get list of databases excluding built-in ones
databases=$(execute_query -d postgres -t -c "
    SELECT datname FROM pg_database 
    WHERE datname NOT IN ('postgres', 'template0', 'template1')
    ORDER BY datname;")

# Get total size of all user databases
echo -e "${BOLD}${BLUE}Total Size of All User Databases:${NC}"
total_size=$(execute_query -d postgres -t -c "
    SELECT pg_size_pretty(SUM(pg_database_size(datname)))
    FROM pg_database
    WHERE datname NOT IN ('postgres', 'template0', 'template1');" | sed 's/^ *//')
echo -e "${GREEN}$total_size${NC}\n"

# Loop through each database
for db in $databases; do
    # Skip empty lines
    [ -z "$db" ] && continue
    
    db=$(echo "$db" | tr -d '[:space:]')
    echo -e "${BOLD}${BLUE}Database: $db${NC}"
    echo -e "${BOLD}${BLUE}----------------------------${NC}"
    
    # Get database size
    db_size=$(execute_query -d "$db" -t -c "SELECT pg_database_size('$db');")
    echo -e "${YELLOW}Total Database Size: $(format_size $db_size)${NC}\n"
    
    # Print table header with fixed width
    printf "${BOLD}%-50s %-15s %-15s %-15s${NC}\n" "Table" "Total Size" "Index Size" "Index Ratio"
    echo -e "${BOLD}$(printf '=%.0s' {1..95})${NC}"
    
    # Get table and index sizes for each schema
    execute_query -d "$db" -t -c "
        WITH RECURSIVE tables AS (
            SELECT 
                schemaname, 
                tablename,
                pg_total_relation_size(schemaname || '.' || tablename) as total_size,
                pg_indexes_size(schemaname || '.' || tablename) as index_size
            FROM pg_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
        )
        SELECT 
            schemaname || '.' || tablename as table_name,
            pg_size_pretty(total_size) as total_size,
            pg_size_pretty(index_size) as index_size,
            CASE 
                WHEN total_size > 0 
                THEN CAST((index_size::float * 100 / total_size::float) as numeric(10,2))
                ELSE 0.00
            END || '%' as index_ratio,
            CASE 
                WHEN total_size > 0 
                THEN CAST(ROUND((index_size::float * 100 / total_size::float)) as integer)
                ELSE 0
            END as ratio_num
        FROM tables
        WHERE total_size > 0
        ORDER BY total_size DESC;" | while read -r line; do
        if [ -n "$line" ]; then
            echo "$line"

            # Parse the line using cut (assuming tab-separated output)
            table_name=$(echo "$line" | cut -f1)
            total_size=$(echo "$line" | cut -f2)
            index_size=$(echo "$line" | cut -f3)
            index_ratio=$(echo "$line" | cut -f4)
            ratio_num=$(echo "$line" | cut -f5)
            
            # Remove leading/trailing whitespace
            table_name=$(echo "$table_name" | xargs)
            total_size=$(echo "$total_size" | xargs)
            index_size=$(echo "$index_size" | xargs)
            index_ratio=$(echo "$index_ratio" | xargs)
            ratio_num=${ratio_num:-0}
            echo "$ratio_num"
            
            # Ensure ratio_num is an integer and compare
            ratio_num=$(printf "%.0f" "$ratio_num")
            
            # Color coding based on the integer value
            if (( ratio_num > 50 )); then
                ratio_color=$RED
            elif (( ratio_num > 30 )); then
                ratio_color=$YELLOW
            else
                ratio_color=$GREEN
            fi

            printf "${CYAN}%-50s${NC} ${GREEN}%-15s${NC} ${BLUE}%-15s${NC} ${ratio_color}%-15s${NC}\n" \
                "$table_name" \
                "$total_size" \
                "$index_size" \
                "$index_ratio"
        fi
    done
    
    echo -e "\n${BOLD}${BLUE}==========================${NC}\n"
done
