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

# Migrate patchesStrategicMerge to patches
grep -rl 'kustomize.toolkit.fluxcd.io' "${envManifestPath}/sync" | xargs -d '\n' -L1 yq -i eval-all '(select(.spec.patchesStrategicMerge != null) | .spec.patchesStrategicMerge[]) |= {"patch": ., "target": {"name": .metadata.name, "kind": .kind}} | (select(.spec.patchesStrategicMerge[].patch.metadata.namespace != null) | .spec.patchesStrategicMerge[].target.namespace) = .spec.patchesStrategicMerge[].patch.metadata.namespace | (select(.spec.patchesStrategicMerge != null) | .spec.patches ) += .spec.patchesStrategicMerge | (select(.spec.patchesJson6902 != null) | .spec.patches) += .spec.patchesJson6902 | del(.spec.patchesStrategicMerge) | del(.spec.patchesJson6902) | (select(.spec.patches != null) | .spec.patches[].patch) |= (. | to_yaml)'

# Add reloader patch to all Kustomizations
find "${envManifestPath}/sync" -name '*.yaml' -exec yq -i '(select(fileIndex==0 and .kind == "Kustomization" and .apiVersion == "kustomize.toolkit.fluxcd.io/*") | .spec.patches) += load("./template/v2.0/reloader-patch.yaml").patches' {} \;

# Delete patch in sync kustomization.yaml
yq -i 'del(.patches)' "${envManifestPath}/kustomization.yaml"

# Delete all .spec.validation from Kustomization
grep -rl 'kustomize.toolkit.fluxcd.io' "${envManifestPath}/sync" | xargs -d '\n' -L1 yq -i 'del(select(.kind == "Kustomization" and .apiVersion == "kustomize.toolkit.fluxcd.io/*") | .spec.validation)'
