#!/bin/bash

set -euo pipefail

environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
workspace_folder="${WORKSPACE}/hosting/rollout/Q2-2025/emissary-resource-patch"

websocket_base_yaml="${workspace_folder}/base/websocket_base.yaml"
kustomization_file="./kustomization.yaml"
websocket_ingress_file="./emissary-ingress-websocket.yaml"

pushd "${environment_manifest_root}/sync/extended" > /dev/null

# Copy the emissary ingress websocket YAML
cp "${websocket_base_yaml}" "${websocket_ingress_file}"

# Update the kustomization.yaml to include the new ingress resource
yq -i eval ".bases += \"${websocket_ingress_file}\"" "${kustomization_file}"

# Apply kustomize to the configuration
kubectl kustomize . > /dev/null

popd > /dev/null