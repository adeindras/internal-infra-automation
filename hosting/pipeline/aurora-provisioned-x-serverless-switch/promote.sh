#!/bin/bash

set -e

CLUSTER_ID="$1"
INSTANCE_ID="$2"

if [[ -z "$INSTANCE_ID" ]]; then
  echo "❌ Usage: $0 <cluster-id> <instance-id>"
  exit 1
fi


echo "🔁 Initiating failover to $INSTANCE_ID (cluster: $CLUSTER_ID)..."

aws rds failover-db-cluster \
  --db-cluster-identifier $CLUSTER_ID \
  --target-db-instance-identifier ${INSTANCE_ID}


echo "✅ Failover triggered to $INSTANCE_ID"
