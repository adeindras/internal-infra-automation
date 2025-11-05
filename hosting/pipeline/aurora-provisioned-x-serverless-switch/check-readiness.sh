#!/bin/bash
set -e

INSTANCE_ID="$1"
TIMEOUT_SECONDS="${2:-3600}" # default 1 hour
INTERVAL=10

if [[ -z "$INSTANCE_ID" ]]; then
  echo "❌ Usage: $0 <instance-id> [timeout-in-seconds]"
  exit 1
fi

echo "🔍 Waiting for $INSTANCE_ID to become available (timeout: $TIMEOUT_SECONDS seconds)..."

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
