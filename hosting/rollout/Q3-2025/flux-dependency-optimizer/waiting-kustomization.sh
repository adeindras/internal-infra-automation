#!/bin/bash

LIST_KS_CHANGES_FILE="$WORKSPACE/hosting/rollout/Q3-2025/flux-dependency-optimizer/list-ks-update-dependson.yaml"
ks_to_check=$(yq e '.kustomization | keys | .[]' "$LIST_KS_CHANGES_FILE")

while read -r KUSTOMIZATION; do
  if [ -z "$KUSTOMIZATION" ]; then
    echo "[INFO] No Kustomization named $ks_to_check. Continue."
    continue
  fi
  
  echo "[INFO] Waiting for Kustomization '$KUSTOMIZATION' in namespace 'flux-system' to become Ready..."
  kubectl -n flux-system wait kustomization/"$KUSTOMIZATION" --for=condition=ready --timeout=2m
    
  if [[ $? -eq 0 ]]; then
    echo "[INFO] Kustomization '$KUSTOMIZATION' is Ready!"
  else
    echo "[INFO] Waiting for kustomization '$KUSTOMIZATION' to be ready..."
  fi
  
done < <(echo "$ks_to_check")