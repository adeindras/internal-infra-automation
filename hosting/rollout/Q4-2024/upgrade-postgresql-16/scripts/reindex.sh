#!/bin/bash

# PostgreSQL Reindex All User Databases Script
# Reindexes all indexes in all user databases, excluding system databases

# Configuration
EXCLUDE_DBS="postgres|template|rdsadmin|azure_maintenance"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
LOG_DIR="/tmp/postgres_reindex_logs"
LOG_FILE="${LOG_DIR}/reindex_${TIMESTAMP}.log"

# Create log directory if it doesn't exist
mkdir -p "$LOG_DIR"

# Function to log messages
log_message() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# Function to reindex a specific database
reindex_database() {
    local db_name="$1"
    
    log_message "Starting reindex for database: $db_name"
    
    # Get list of all indexes in the database
    local indexes=$(psql -d "$db_name" -t -A -c "
        SELECT schemaname || '.' || indexname 
        FROM pg_indexes 
        WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
    ")
    
    # Reindex each index
    while IFS= read -r index; do
        log_message "Reindexing: $index"
        psql -d "$db_name" -c "REINDEX INDEX CONCURRENTLY $index;" 2>&1 | tee -a "$LOG_FILE"
    done <<< "$indexes"
    
    log_message "Completed reindex for database: $db_name"
}

# Main script
main() {
    
    # Get list of all databases, excluding system databases
    local databases=$(psql -d postgres -t -A -c "
        SELECT datname 
        FROM pg_database 
        WHERE datname !~ '$EXCLUDE_DBS'
    ")
    
    # Check if any user databases exist
    if [ -z "$databases" ]; then
        log_message "No user databases found to reindex."
        exit 0
    fi
    
    log_message "Starting comprehensive database reindexing"
    log_message "Excluded databases: $EXCLUDE_DBS"
    
    # Reindex each database
    while IFS= read -r db; do
        reindex_database "$db"
    done <<< "$databases"
    
    log_message "Comprehensive database reindexing completed"
    log_message "Detailed logs available at: $LOG_FILE"
}

# Error handling
set -e
trap 'log_message "Error: Command failed with error code $?"' ERR

# Run the main script
main