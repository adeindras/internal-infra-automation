#!/bin/bash
set -e

INSTANCE_ID="$1"
ENGINE_MODE="$2"
INSTANCE_CLASS="$3" #If engine mode is provisioned

if [[ -z "$INSTANCE_ID" || -z "$ENGINE_MODE" ]]; then
  echo "❌ Usage: $0 <cluster-id> <engine-mode> <instance-class>"
  exit 1
fi

echo "➕ Modifying instance: $INSTANCE_ID"

CREATE_CMD=(
 aws rds modify-db-instance 
 --db-instance-identifier $INSTANCE_ID
 --apply-immediately
 )


if [[ "$ENGINE_MODE" == "serverless" ]]; then
  CREATE_CMD+=( --db-instance-class db.serverless)
  INSTANCE_CLASS="db.serverless"
else
  CREATE_CMD+=( --db-instance-class $INSTANCE_CLASS)
fi

"${CREATE_CMD[@]}"
echo "✅ Instance $INSTANCE_ID modified with instance class $INSTANCE_CLASS"