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

# Ensure that prune = false in flux and flux-system kustomization
fluxPrune=$(kubectl get kustomization -n flux-system flux -o jsonpath='{@.spec.prune}')
fluxSystemPrune=$(kubectl get kustomization -n flux-system flux-system -o jsonpath='{@.spec.prune}')
fluxVolumePrune=$(kubectl get kustomization -n flux-system flux-volume -o jsonpath='{@.spec.prune}') || true

# Disable Pruning
if [[ $fluxSystemPrune == "true" ]]; then
  echo "flux-system Kustomization pruning is enabled"
  echo "disabling flux-system Kustomization pruning"
  kubectl patch kustomization -n flux-system  --type='json' -p='[{"op": "replace", "path": "/spec/prune", "value":false}]' flux-system
fi

if [[ $fluxPrune == "true" ]]; then
  echo "flux Kustomization pruning is still enabled"
  echo "disabling flux-system Kustomization pruning"
  kubectl patch kustomization -n flux-system  --type='json' -p='[{"op": "replace", "path": "/spec/prune", "value":false}]' flux
fi

if [[ $fluxVolumePrune == "true" ]]; then
  echo "flux-volume Kustomization pruning is enabled"
  echo "disabling flux-volume Kustomization pruning"
  kubectl patch kustomization -n flux-system  --type='json' -p='[{"op": "replace", "path": "/spec/prune", "value":false}]' flux-volume
fi

# Delete flux kustomization from the cluster
yq -i 'del(.bases[] | select(. == "./flux.yaml") | .)' "${envManifestPath}/sync/core/kustomization.yaml"
yq -i 'del(.bases[] | select(. == "./flux-volume.yaml") | .)' "${envManifestPath}/sync/core/kustomization.yaml"
rm -f "${envManifestPath}/sync/core/flux.yaml"
rm -f "${envManifestPath}/sync/core/flux-volume.yaml"

# Remove flux from dependencies
(grep -rl "\- name: flux" "${envManifestPath}/sync" || [ "$?" == "1" ]) | xargs -r -d '\n' -L1 yq -i 'del( (.spec.dependsOn[] | select (.name == "flux")) | .)'

# Change iac-repo ref to flux-system
grep -rl 'kind: Kustomization' "${envManifestPath}/sync" | xargs -d '\n' -L1 yq -i '(select(.apiVersion == "kustomize.toolkit.fluxcd.io/*" and .kind == "Kustomization" and .spec.sourceRef.name == "iac-repo") | .spec.sourceRef.name) = "flux-system"'

# Delete iac repo
yq -i 'del(select(.kind == "GitRepository" and .metadata.name == "iac-repo"))' "${envManifestPath}/sync/core/repo.yaml"


# Patch ESO to change cluster-secret-store repo to flux-system
yq -i '(select(fileIndex==0 and .kind == "Kustomization" and .apiVersion == "kustomize.toolkit.fluxcd.io/*" and .metadata.name == "external-secrets-operator") | .spec.patchesStrategicMerge) = load("./template/v0.37/eso-patch.yaml").patchesStrategicMerge' "${envManifestPath}/sync/core/external-secrets-operator.yaml"

yq -i '(select(fileIndex==0 and .kind == "Kustomization" and .apiVersion == "kustomize.toolkit.fluxcd.io/*" and .metadata.name == "external-secrets-operator") | .spec.patchesJson6902) = load("./template/v0.37/eso-patch.yaml").patchesJson6902' "${envManifestPath}/sync/core/external-secrets-operator.yaml"

# Generate replacement manifest for flux controller
# Create infra-controllers manifest
# update flux-system content to sync flux-controllers and infra controllers
mkdir -p "${envManifestPath}/cluster-system/flux-system"
cat "./template/v0.37/infrastructure.yaml" | envsubst > "${envManifestPath}/cluster-system/infrastructure.yaml"
cat "./template/v0.37/flux-system/kustomization.yaml" | envsubst > "${envManifestPath}/cluster-system/flux-system/kustomization.yaml"
cat "./template/v0.37/flux-system/gotk-sync.yaml" | envsubst > "${envManifestPath}/cluster-system/flux-system/gotk-sync.yaml"

./flux037/flux install --export --namespace=flux-system --toleration-keys=CriticalOnInitOnly > "${envManifestPath}/cluster-system/flux-system/gotk-components.yaml"
