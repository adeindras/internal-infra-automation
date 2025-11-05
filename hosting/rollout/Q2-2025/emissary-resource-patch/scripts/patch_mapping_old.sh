#!/bin/bash

set -euo pipefail

if ! [ -x "$(command -v yq)" ]; then
  echo 'Error: yq is not installed.' >&2
  exit 1
fi

# Define variables
environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
websocket_folder="${WORKSPACE}/deployments/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/services/emissary-ingress"
workspace_folder="${WORKSPACE}/hosting/rollout/Q2-2025/emissary-resource-patch"
temp_yaml="/tmp/websocket-split-paths.yaml"

websocket_ingress_yaml="./justice-websocket-ingress.yaml"
ingress_base_yaml="${workspace_folder}/base/ingress_base.yaml"
kustomization_file="kustomization.yaml"

# Extract ROUTE53_PUBLIC_ZONE_NAME from cluster-variables.yaml
environment_url=$(yq '.data.ROUTE53_PUBLIC_ZONE_NAME' "${environment_manifest_root}/cluster-variables/cluster-variables.yaml")

if [[ -z "${environment_url}" ]]; then
  echo "Error: ROUTE53_PUBLIC_ZONE_NAME is empty or not set in cluster-variables.yaml"
  exit 1
fi

pushd "${websocket_folder}" > /dev/null

# Update YAML files to set ambassador_id for websocket upgrades
find . -name "*.yaml" -print0 | xargs -0 -L 1 -- yq -i eval \
  'select(.spec.allow_upgrade[0] == "websocket").spec.ambassador_id |= ["websocket"]'

# Generate the split paths for websocket services
yq -N eval-all \
  'select(.spec.allow_upgrade[0] == "websocket").spec.prefix | 
  [{"backend": {"service": {"name": "emissary-ingress-websocket", "port": {"number": 443}}}, "path": ., "pathType": "Prefix" }]' \
  ./*.yaml > "${temp_yaml}"

# Add the justice websocket ingress resource to kustomization.yaml
yq -i eval '.resources += "./justice-websocket-ingress.yaml"' "${kustomization_file}"

# Merge the split paths into the ingress base file to create the websocket ingress configuration
yq -P eval '.spec.rules[0].http.paths |= load("'"${temp_yaml}"'")' "${ingress_base_yaml}" > "${websocket_ingress_yaml}"
yq -i ".spec.rules[0].host = \"${environment_url}\"" "${websocket_ingress_yaml}"

rm -f "${temp_yaml}"

kubectl kustomize . > /dev/null

popd > /dev/null

echo "The split is done. Please check the files at ${workspace_folder}"