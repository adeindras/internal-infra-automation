#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="linkerd-viz"


echo "🔍 Checking if namespace '$NAMESPACE' exists..."
if ! kubectl get ns "$NAMESPACE" >/dev/null 2>&1; then
  echo "⚠️ Namespace '$NAMESPACE' not found. Nothing to clean up."
  exit 0
fi

echo "🔍 Checking for pods in namespace '$NAMESPACE'..."
PODS=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null || true)

if [[ -n "$PODS" ]]; then
  echo "⚠️ Pods still exist in '$NAMESPACE'. Cleanup skipped to avoid disruption."
  echo "   Please remove remaining workloads first."
  exit 0
fi

echo "✅ No pods found in '$NAMESPACE'. Proceeding with APIService cleanup..."

# Get apiservices that reference linkerd-viz
APISERVICES=$(kubectl get apiservices -o name | grep "v1alpha1.tap.linkerd.io" || true)

if [[ -n "$APISERVICES" ]]; then
  echo "➡️ Deleting APIService objects for linkerd-viz..."
  for svc in $APISERVICES; do
    if ! kubectl delete "$svc" --ignore-not-found --wait=true; then
      echo "⚠️ Failed to delete $svc, continuing..."
    fi
  done
else
  echo "ℹ️ No APIService objects found for linkerd-viz."
fi

echo "➡️ Deleting namespace '$NAMESPACE'..."
if ! kubectl delete ns "$NAMESPACE" --ignore-not-found --wait=true; then
  echo "⚠️ Failed to delete namespace '$NAMESPACE'. Please check finalizers or resources stuck."
  exit 0
fi

echo "✅ Cleanup of '$NAMESPACE' completed successfully."
