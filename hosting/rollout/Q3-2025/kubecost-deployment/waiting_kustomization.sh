#!/bin/bash

KUSTOMIZATION="kubecost"
DEPLOYMENT="kubecost-cost-analyzer"
TIMEOUT=180  # seconds
INTERVAL=5   # seconds
ELAPSED=0

echo "[INFO] Waiting for Kustomization '$KUSTOMIZATION' to be fully ready in namespace 'flux-system'..."

# Wait until kustomization exists
until kubectl get kustomization "$KUSTOMIZATION" -n "flux-system" &>/dev/null; do
  echo "[INFO] Waiting for kustomization to be created..."
  sleep $INTERVAL
  ELAPSED=$((ELAPSED + INTERVAL))
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "[WARN] Timed out waiting for kustomization to appear."
    exit 1
  fi
done

# Wait until deployments shows all replicas available
ELAPSED=0
until [ "$(kubectl get deploy "$DEPLOYMENT" -n "kubecost" -o jsonpath='{.status.readyReplicas}')" == "$(kubectl get deploy "$DEPLOYMENT" -n "kubecost" -o jsonpath='{.spec.replicas}')" ]; do
  echo "[INFO] Waiting for all replicas to become ready..."
  sleep $INTERVAL
  ELAPSED=$((ELAPSED + INTERVAL))
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "[WARN] Timed out waiting for deployment to become ready."
    exit 1
  fi
done

echo "[INFO] Deployment '$DEPLOYMENT' is fully ready!"
exit 0