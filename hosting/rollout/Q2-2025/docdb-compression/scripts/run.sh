#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -uo pipefail

trap err ERR

err () {
  echo "The script is encountering an error on line $(caller)." >&2
  code=$?
  kubectl delete -n default -k ../jumpbox/manifests
  exit $code
}


if (( $# == 0 )); then
  kubectl create -n default -k ../jumpbox/manifests
  kubectl wait -n default --for=condition=Ready pod/docdb-compression --timeout=300s
  echo "Running DocumentDB compression"
  kubectl exec -n default docdb-compression -- bash -c 'mongosh $MONGO_HOST -u $MONGO_USERNAME -p $MONGO_PASSWORD -f /data/db/scripts/enable_compression.js'
  kubectl delete -n default -k ../jumpbox/manifests
  exit 0
fi

if [[ "${1}" == "--dry-run" ]]; then
  kubectl create -n default -k ../jumpbox/manifests
  kubectl wait -n default --for=condition=Ready pod/docdb-compression --timeout=300s
  echo "Running DocumentDB compression in dry-run mode"
  kubectl exec -n default docdb-compression -- bash -c 'mongosh $MONGO_HOST -u $MONGO_USERNAME -p $MONGO_PASSWORD -f /data/db/scripts/dry_run_compression.js'
  kubectl delete -n default -k ../jumpbox/manifests
else
  echo "Usage: run.sh [--dry-run]" >&2
fi
