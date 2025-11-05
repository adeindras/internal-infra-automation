#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

cp -r "${SCRIPT_DIR}/sql-checker" "${SCRIPT_DIR}/generated-sql-checker"

# envsubst
CLUSTER_NAME="${CLUSTER_NAME}" envsubst < "${SCRIPT_DIR}/sql-checker/vpc.sql" > "${SCRIPT_DIR}/generated-sql-checker/vpc.sql"

# Run steampipe
echo "[+] Obtaining RDS resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/rds.sql" --output json > rds.json || exit 1

echo "[+] Obtaining DocDB resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/docdb.sql" --output json > docdb.json || exit 1

echo "[+] Obtaining Elasticache resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/elasticache.sql" --output json > elasticache.json || exit 1

echo "[+] Obtaining MSK resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/msk.sql" --output json > msk-a.json || exit 1
steampipe query "${SCRIPT_DIR}/generated-sql-checker/msk-plain.sql" --output json > msk-b.json || exit 1
check_msk_a=$(yq -P '.rows[].resource_name' msk-a.json | grep -c "${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}")
check_msk_b=$(yq -P '.rows[].resource_name' msk-b.json | grep -c "${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}")

if [[ $check_msk_a -ge 1 ]]; then
    cp "msk-a.json" "msk.json"
else
    cp "msk-b.json" "msk.json"
fi

echo "[+] Obtaining EKS resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/eks.sql" --output json > eks.json || exit 1

echo "[+] Obtaining Eventbridge resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/eventbridge.sql" --output json > eventbridge.json || exit 1

echo "[+] Obtaining VPC resources ..."
steampipe query "${SCRIPT_DIR}/generated-sql-checker/vpc.sql" --output json > vpc.json || exit 1
