#!/bin/bash
set -e

CLUSTER_ID="$1"

if [[ -z "$CLUSTER_ID" ]]; then
  echo "❌ Usage: $0 <cluster-id>"
  exit 1
fi

echo "🔍 Fetching current DB cluster members..."
CLUSTER_MEMBERS=$(aws rds describe-db-clusters \
  --db-cluster-identifier "$CLUSTER_ID" \
  --query "DBClusters[0].DBClusterMembers" \
  --output json)

# Loop through members and update readers' promotion tier to 2
echo "$CLUSTER_MEMBERS" | jq -c '.[]' | while read member; do
  INSTANCE_ID=$(echo "$member" | jq -r '.DBInstanceIdentifier')
  IS_WRITER=$(echo "$member" | jq -r '.IsClusterWriter')

  # if [[ "$IS_WRITER" == "false" ]]; then

  TIMEOUT_SECONDS="${2:-3600}" # default 1 hour
  INTERVAL=15
  echo "🔍 Waiting for $INSTANCE_ID to become available before changing the promotion tier (timeout: $TIMEOUT_SECONDS seconds)..."

  start_time=$(date +%s)
  spinner='|/-\'
  spin_index=0

  while true; do
    status=$(aws rds describe-db-instances --db-instance-identifier "$INSTANCE_ID" \
      --query 'DBInstances[0].DBInstanceStatus' --output text 2>/dev/null)

    current_time=$(date +%s)
    elapsed=$((current_time - start_time))

    if [[ "$status" == "available" ]]; then
      echo -e "\r✅ $INSTANCE_ID is now available (waited ${elapsed}s)"
      break
    fi

    if (( elapsed >= TIMEOUT_SECONDS )); then
      echo -e "\r❌ Timeout reached ($TIMEOUT_SECONDS seconds) — $INSTANCE_ID is still '$status'"
      exit 1
    fi

    printf "\r⏳ Checking availability (%s) %-4s" "$status" "${spinner:$spin_index:1}"
    spin_index=$(( (spin_index + 1) % 4 ))

    sleep "$INTERVAL"
  done

  echo "🔄 Updating $INSTANCE_ID to promotion tier 2"
  aws rds modify-db-instance \
    --db-instance-identifier "$INSTANCE_ID" \
    --promotion-tier 2 \
    --apply-immediately
  #Handle delay in AWS Backend
  sleep 10
# fi
done