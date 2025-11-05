#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

# Available environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "Environment Name: ${ENVIRONMENT_NAME}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Workspace: ${WORKSPACE}"

envManifestPath="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}"

echo "$envManifestPath"

# Download flux 0.37.0
if [[ ! -e flux037 || ! -f flux037/flux ]]; then 
  echo "Downloading Flux 0.37.0"
  mkdir flux037
  curl -L -o - https://github.com/fluxcd/flux2/releases/download/v0.37.0/flux_0.37.0_linux_amd64.tar.gz | tar -C flux037 -xz
fi

# Delete flux controller kustomizations from repository
kubectl delete kustomization -n flux-system flux || true
kubectl delete kustomization -n flux-system flux-volume || true

kubectl annotate kustomization -n flux-system flux-system kubernetes.io/last-applied-configuration-
kubectl annotate gitrepository -n flux-system flux-system kubernetes.io/last-applied-configuration-
kubectl apply -f "${envManifestPath}/cluster-system/flux-system/gotk-sync.yaml"
kubectl wait -n flux-system kustomization/flux-system --for=condition=ready --timeout=5m
# kubectl wait -n flux-system kustomization/infra-controllers --for=condition=ready --timeout=5m

# Run flux-check
./flux037/flux check 

# write warning events & error logs
kubectl get events -n flux-system --field-selector type=Warning > flux-events-warning.log
./flux037/flux logs --all-namespaces --level=error > flux-controller-error.log

# Delete iac-repo
kubectl delete gitrepository -n flux-system iac-repo
