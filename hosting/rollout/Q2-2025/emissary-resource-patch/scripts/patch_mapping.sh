#!/bin/bash

set -euo pipefail

if ! [ -x "$(command -v yq)" ]; then
  echo 'Error: yq is not installed.' >&2
  exit 1
fi

# Define variables
environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
justice_service_folder="${WORKSPACE}/deployments/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}"
workspace_folder="${WORKSPACE}/hosting/rollout/Q2-2025/emissary-resource-patch"
split_paths_file="/tmp/websocket-split-paths.yaml"

ingress_base_file="${workspace_folder}/base/ingress_base.yaml"
websocket_ingress_file="./justice-websocket-ingress.yaml"
kustomization_file="./kustomization.yaml"

# Extract ROUTE53_PUBLIC_ZONE_NAME from cluster-variables.yaml
environment_url=$(yq '.data.ROUTE53_PUBLIC_ZONE_NAME' "${environment_manifest_root}/cluster-variables/cluster-variables.yaml")

if [[ -z "${environment_url}" ]]; then
  echo "Error: ROUTE53_PUBLIC_ZONE_NAME is empty or not set in cluster-variables.yaml"
  exit 1
fi

if [ -d "${justice_service_folder}/services/emissary-ingress" ]; then
  websocket_folder="${justice_service_folder}/services/emissary-ingress"
else
  websocket_folder="${justice_service_folder}/services-overlay/emissary-ingress"
fi

# Update ambassador_id for websocket upgrade cases
pushd "${justice_service_folder}" > /dev/null

find . -type f -path "*/infrastructure/ingress/path-mapping.yaml" | while read -r file; do
  yq -i eval 'select(.spec.allow_upgrade[0] == "websocket").spec.ambassador_id |= ["websocket"]' "$file"
done

# Extract relevant paths and generate a split-paths file for websocket
find . -type f -path "*/infrastructure/ingress/path-mapping.yaml" -print0 | xargs -0 yq -N eval-all \
  'select(.spec.allow_upgrade[0] == "websocket").spec.prefix | 
  [{"backend": {"service": {"name": "emissary-ingress-websocket", "port": {"number": 443}}}, "path": ., "pathType": "Prefix" }]' > "${split_paths_file}"

popd > /dev/null

# Generate the final justice-websocket-ingress.yaml
pushd "${websocket_folder}" > /dev/null

yq eval -P ".spec.rules[0].http.paths |= load(\"${split_paths_file}\")" "${ingress_base_file}" > "${websocket_ingress_file}"
yq -i ".spec.rules[0].host = \"${environment_url}\"" "${websocket_ingress_file}"

rm -f "${split_paths_file}"

# Add the websocket ingress resource to kustomization.yaml
yq -i eval ".resources += \"${websocket_ingress_file}\"" "${kustomization_file}"
kubectl kustomize . > /dev/null

popd > /dev/null

echo "The split is done."
echo "Please check the files at:"
echo "  - ${websocket_folder}"
echo "  - ${justice_service_folder}"