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

kubectl create -n default -k ../jumpbox/manifests
kubectl wait -n default --for=condition=Ready pod/docdb-compression --timeout=300s

VERIFICATION_RESULT=$(kubectl exec -n default docdb-compression -- bash -c 'mongosh $MONGO_HOST -u $MONGO_USERNAME -p $MONGO_PASSWORD -f /data/db/scripts/verify_compression.js')
COMPRESSION_DISABLED_COLLECTION_COUNT=$(echo "${VERIFICATION_RESULT}" | jq '[.[] | .collections[] | select(.compressionEnabled == false)] | length')
echo ${VERIFICATION_RESULT} | jq > $1
echo "verification result is written to $1" >&2
echo "Collections with compression disabled: ${COMPRESSION_DISABLED_COLLECTION_COUNT}"
if (( $COMPRESSION_DISABLED_COLLECTION_COUNT > 0 )); then
  echo "There are collections with disabled compression." >&2
  kubectl delete -n default -k ../jumpbox/manifests
  exit 1
fi

echo "All collections are compressed!" >&2
kubectl delete -n default -k ../jumpbox/manifests
