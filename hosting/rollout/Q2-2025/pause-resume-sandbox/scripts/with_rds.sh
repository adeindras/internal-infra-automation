#!/bin/bash

echo "Chosen action: $ACTION"

# Function to check RDS instance status
check_rds_status() {
  INSTANCE_IDENTIFIER=$1
  REGION=$2
  EXPECTED_STATUS=$3

  # Consider these as acceptable terminal states
  ACCEPTABLE_STATES=("storage-full" "backing-up" "modifying" "storage-optimization")

  while true; do
    STATUS=$(aws rds describe-db-instances \
      --db-instance-identifier "${INSTANCE_IDENTIFIER}" \
      --region "${REGION}" \
      --query "DBInstances[0].DBInstanceStatus" \
      --output text 2>/dev/null)

    echo "Instance '${INSTANCE_IDENTIFIER}' status: ${STATUS}"

    if [[ "${STATUS}" == "${EXPECTED_STATUS}" ]]; then
      echo "${INSTANCE_IDENTIFIER} is now ${EXPECTED_STATUS}."
      break
    fi

    for state in "${ACCEPTABLE_STATES[@]}"; do
      if [[ "${STATUS}" == "$state" ]]; then
        echo "${INSTANCE_IDENTIFIER} is in acceptable intermediate state: ${STATUS}."
        break 2
      fi
    done

    sleep 5
  done
}

if [[ "$ACTION" == "RESUME" ]]; then

  # Starting RDS instances
  echo "Starting RDS instances..."

  for INSTANCE in analytics justice; do
    echo "Starting RDS instance: rds-${CLUSTER_NAME}-${INSTANCE}"
    aws rds start-db-instance \
      --db-instance-identifier "rds-${CLUSTER_NAME}-${INSTANCE}" \
      --region "${REGION_NAME}" > /dev/null 2>&1

    check_rds_status "rds-${CLUSTER_NAME}-${INSTANCE}" "${REGION_NAME}" "available" &
  done

  wait
  echo "All instances are now available or in acceptable states."

elif [[ "$ACTION" == "PAUSE" ]]; then

  # Stopping RDS instances
  echo "Stopping RDS instances..."

  for INSTANCE in analytics justice; do
    echo "Stopping RDS instance: rds-${CLUSTER_NAME}-${INSTANCE}"
    aws rds stop-db-instance \
      --db-instance-identifier "rds-${CLUSTER_NAME}-${INSTANCE}" \
      --region "${REGION_NAME}" > /dev/null 2>&1
  done

  wait
  echo "All instances are now stopped."

  # Fetch snapshot identifiers
  echo "Fetching all manual RDS snapshots in region ${REGION_NAME}..."
  snapshots=$(aws rds describe-db-snapshots \
    --region "$REGION_NAME" \
    --snapshot-type manual \
    --query 'DBSnapshots[*].DBSnapshotIdentifier' \
    --output text)

  if [ -z "$snapshots" ]; then
    echo "No manual snapshots found. Nothing to delete."
    exit 0
  fi

  echo "Found the following manual snapshots:"
  echo "$snapshots"
  echo "Deleting..."

  for snapshot in $snapshots; do
    if aws rds delete-db-snapshot \
        --region "$REGION_NAME" \
        --db-snapshot-identifier "$snapshot" > /dev/null 2>&1; then
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
