#!/bin/bash
set -e

INSTANCE_ID="$1"
TIMEOUT_SECONDS="${4:-3600}" # default 1 hour
INTERVAL=10

echo "Deleting: $INSTANCE_ID"
aws rds delete-db-instance --db-instance-identifier "$INSTANCE_ID" --skip-final-snapshot


start_time=$(date +%s)
spinner='|/-\'
spin_index=0

echo "Waiting for deletion of $INSTANCE_ID (timeout: $TIMEOUT_SECONDS seconds)..."
while true; do
  current_time=$(date +%s)
  elapsed=$((current_time - start_time))

  if ! aws rds describe-db-instances --db-instance-identifier "$INSTANCE_ID" &>/dev/null; then
    echo "Instance deleted (waited ${elapsed}s)"
    break
  fi

  if (( elapsed >= TIMEOUT_SECONDS )); then
    echo -e "\r❌ Timeout reached ($TIMEOUT_SECONDS seconds) — $INSTANCE_ID is still 'Deleting'"
    exit 1
  fi
  
  printf "\r⏳ Checking deletion %-4s" "${spinner:$spin_index:1}"
  spin_index=$(( (spin_index + 1) % 4 ))

  sleep "$INTERVAL"
done

