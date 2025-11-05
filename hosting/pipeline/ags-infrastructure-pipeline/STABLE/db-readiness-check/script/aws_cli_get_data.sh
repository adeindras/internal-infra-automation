#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# cp -r "${SCRIPT_DIR}/sql-checker" "${SCRIPT_DIR}/generated-sql-checker"

# envsubst
# CLUSTER_NAME="${CLUSTER_NAME}" envsubst < "${SCRIPT_DIR}/sql-checker/vpc.sql" > "${SCRIPT_DIR}/generated-sql-checker/vpc.sql"

# Run aws cli
echo "[+] Obtaining DocDB instances resources ..."
DOCDB_INSTANCES_SEARCH_QUERY="docdb-cluster-${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}.*"
aws docdb describe-db-instances | yq '{"rows" : [.DBInstances[] | select(.DBClusterIdentifier | test(strenv(DOCDB_INSTANCES_SEARCH_QUERY)))]}' -ojson | sed 's/DBInstanceIdentifier/resource_name/g' 1> docdb_instances.json
# aws docdb describe-db-instances | yq '{"rows" : [.DBInstances[] | select(.DBClusterIdentifier == "docdb-cluster-sandbox-justice-dev-os")]}' -ojson 1> docdb-instances.json
# steampipe query "${SCRIPT_DIR}/generated-sql-checker/docdb.sql" --output json > docdb.json || exit 1


