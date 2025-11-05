#!/bin/bash
set -e

CLUSTER_ID="$1"
INSTANCE_ID="$2"
TIMEOUT_SECONDS="${3:-300}"  # Default: 1 hour
INTERVAL=10

if [[ -z "$INSTANCE_ID" ]]; then
  echo "❌ Usage: $0 <cluster-id> <instance-id> [timeout-in-seconds]"
  exit 1
fi

echo "🔍 Waiting for $INSTANCE_ID to become WRITER (timeout: $TIMEOUT_SECONDS seconds)..."


if [[ -z "$CLUSTER_ID" || "$CLUSTER_ID" == "None" ]]; then
  echo "❌ Unable to resolve cluster for instance: $INSTANCE_ID"
  exit 1
fi

start_time=$(date +%s)
spinner='|/-\'
spin_index=0

while true; do
  WRITER_INSTANCE=$(aws rds describe-db-clusters \
    --db-cluster-identifier "$CLUSTER_ID" \
    --query "DBClusters[0].DBClusterMembers[?IsClusterWriter==\`true\`].DBInstanceIdentifier" \
    --output text 2>/dev/null)

  current_time=$(date +%s)
  elapsed=$((current_time - start_time))

  if [[ "$WRITER_INSTANCE" == "$INSTANCE_ID" ]]; then
    echo -e "\r✅ $INSTANCE_ID is now the WRITER (waited ${elapsed}s)"
    break
  fi

  if (( elapsed >= TIMEOUT_SECONDS )); then
    echo -e "\r❌ Timeout reached (${TIMEOUT_SECONDS}s) — $INSTANCE_ID is not the WRITER (currently: $WRITER_INSTANCE)"
    exit 1
  fi

  printf "\r⏳ Waiting for promotion to WRITER (currently: %s) %-4s" "$WRITER_INSTANCE" "${spinner:$spin_index:1}"
  spin_index=$(( (spin_index + 1) % 4 ))

  sleep "$INTERVAL"
done
