#!/bin/bash

echo "Chosen action: $ACTION"

CLUSTER_IDENTIFIER="docdb-cluster-${CLUSTER_NAME}-os"

# Function to check DocDB cluster status
check_docdb_status() {
  CLUSTER_IDENTIFIER=$1
  REGION=$2
  EXPECTED_STATUS=$3

  while true; do
    STATUS=$(aws docdb describe-db-clusters \
      --db-cluster-identifier "${CLUSTER_IDENTIFIER}" \
      --region "${REGION}" \
      --query "DBClusters[0].Status" \
      --output text 2>/dev/null)

    echo "Cluster '${CLUSTER_IDENTIFIER}' status: ${STATUS}"
    if [[ "${STATUS}" == "${EXPECTED_STATUS}" ]]; then
      echo "${CLUSTER_IDENTIFIER} is now ${EXPECTED_STATUS}."
      break
    fi
    sleep 5
  done
}

if [[ "$ACTION" == "RESUME" ]]; then

  # Starting RDS instance
  echo "Starting DocDB cluster..."

  aws docdb start-db-cluster \
    --db-cluster-identifier "${CLUSTER_IDENTIFIER}" \
    --region "${REGION_NAME}" > /dev/null 2>&1

  check_docdb_status "${CLUSTER_IDENTIFIER}" "${REGION_NAME}" "available"

  echo "DocDB cluster is now available."

elif [[ "$ACTION" == "PAUSE" ]]; then

  # Stoping RDS instance
  echo "Stopping DocDB cluster..."

  aws docdb stop-db-cluster \
    --db-cluster-identifier "${CLUSTER_IDENTIFIER}" \
    --region "${REGION_NAME}" > /dev/null 2>&1

  echo "DocDB cluster is now stopped."

  # Fetch manual snapshot identifiers
  echo "Fetching all manual DocDB snapshots in region ${REGION_NAME}..."
  snapshots=$(aws docdb describe-db-cluster-snapshots \
    --region "$REGION_NAME" \
    --snapshot-type manual \
    --query 'DBClusterSnapshots[*].DBClusterSnapshotIdentifier' \
    --output text)

  if [ -z "$snapshots" ]; then
    echo "No manual snapshots found. Nothing to delete."
    exit 0
  fi

  echo "Found the following manual snapshots:"
  echo "$snapshots"
  echo "Deleting..."

  for snapshot in $snapshots; do
    aws docdb delete-db-cluster-snapshot \
      --region "$REGION_NAME" \
      --db-cluster-snapshot-identifier "$snapshot" > /dev/null 2>&1

    if aws docdb delete-db-cluster-snapshot --region "$REGION_NAME" \
        --db-cluster-snapshot-identifier "$snapshot" > /dev/null 2>&1; then
      echo "Successfully deleted: $snapshot"
    else
      echo "Failed to delete: $snapshot"
    fi
  done

  echo "All deletions attempted."

else
  echo "Unknown action: $ACTION"
  exit 1
fi
