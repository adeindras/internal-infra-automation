#!/bin/bash
set -euo pipefail

usage() {
  cat <<EOF
Usage: $0 [options]

Options:
  -c <cluster-id>         (required) RDS cluster identifier
  -r <reader-id>          (required) New reader instance identifier
  -m <engine-mode>        (required) Engine mode: provisioned | serverless
  -i <instance-class>     (optional) Instance class (required if mode=provisioned)
  -h                      Show this help message

Example:
  $0 -c my-cluster -r reader-1 -m provisioned -i db.r6g.large
  $0 -c my-cluster -r reader-2 -m serverless
EOF
}

# Defaults
CLUSTER_ID=""
READER_INSTANCE_ID=""
ENGINE_MODE=""
INSTANCE_CLASS=""

while getopts ":c:r:m:i:v:h" opt; do
  case $opt in
    c) CLUSTER_ID="$OPTARG" ;;
    r) READER_INSTANCE_ID="$OPTARG" ;;
    m) ENGINE_MODE="$OPTARG" ;;
    i) INSTANCE_CLASS="$OPTARG" ;;
    h) usage; exit 0 ;;
    \?) echo "❌ Invalid option -$OPTARG"; usage; exit 1 ;;
    :) echo "❌ Option -$OPTARG requires an argument."; usage; exit 1 ;;
  esac
done

# Validation
if [[ -z "$CLUSTER_ID" || -z "$READER_INSTANCE_ID" || -z "$ENGINE_MODE" ]]; then
  echo "❌ Missing required arguments"
  usage
  exit 1
fi

if [[ "$ENGINE_MODE" == "provisioned" && -z "$INSTANCE_CLASS" ]]; then
  echo "❌ --instance-class (-i) is required when engine mode is 'provisioned'"
  usage
  exit 1
fi

if aws rds describe-db-instances --db-instance-identifier "$READER_INSTANCE_ID" >/dev/null 2>&1; then
  echo "⚠️  Reader instance '$READER_INSTANCE_ID' already exists, skipping creation."
  exit 0
fi

# Add the new reader
echo "➕ Creating new reader instance: $READER_INSTANCE_ID"

CREATE_CMD=(
  aws rds create-db-instance
  --db-cluster-identifier "$CLUSTER_ID"
  --db-instance-identifier "$READER_INSTANCE_ID"
  --enable-performance-insights
  --engine aurora-postgresql
  --promotion-tier 0
)

if [[ "$ENGINE_MODE" == "serverless" ]]; then
  CREATE_CMD+=( --db-instance-class db.serverless )
else
  CREATE_CMD+=( --db-instance-class "$INSTANCE_CLASS" )
fi

"${CREATE_CMD[@]}"
echo "✅ Reader $READER_INSTANCE_ID created with promotion tier 0"
