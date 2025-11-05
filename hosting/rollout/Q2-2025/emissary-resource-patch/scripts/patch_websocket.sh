#!/bin/bash

# Set environment and file paths
environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
websocket_file="${environment_manifest_root}/sync/extended/emissary-ingress-websocket.yaml"

# Load expected values from the expected_values.json file
if [[ "$CCU_SETUP" == "<1k" ]]; then
  expected_json_file="base/expected_values_1k.json"
else
  expected_json_file="base/expected_values.json"
fi

websocket_vars=$(jq -r '.websocket | to_entries | map("\(.key)=\(.value)") | .[]' "$expected_json_file")
websocket_version=$(jq -r '.websocket.WEBSOCKET_VERSION' "$expected_json_file")

# Helper function to update or add key-value pairs in the YAML file
update_yaml() {
  local key=$1
  local value=$2
  echo "Updating $key with value $value"
  yq eval ".spec.postBuild.substitute.$key = \"$value\"" -i "$websocket_file"
}

for var in $websocket_vars; do
  key=$(echo $var | cut -d '=' -f 1)
  value=$(echo $var | cut -d '=' -f 2)

  if [ "$key" == "WEBSOCKET_VERSION" ]; then
    continue
  fi

  if yq eval ".spec.postBuild.substitute.$key" "$websocket_file" &>/dev/null; then
    update_yaml "$key" "$value"
  else
    echo "Adding $key with value $value"
    update_yaml "$key" "$value"
  fi
done

# Helper function to remove a key from the YAML file
remove_yaml_key() {
  local key=$1
  echo "Removing $key"
  yq eval "del(.spec.postBuild.substitute.$key)" -i "$websocket_file"
}

for key in "EMISSARY_WEBSOCKET_CPU_REQUEST" "EMISSARY_WEBSOCKET_MEMORY_REQUEST" "EMISSARY_WEBSOCKET_MEMORY_LIMIT"; do
  if yq eval ".spec.postBuild.substitute.$key" "$websocket_file" &>/dev/null; then
    remove_yaml_key "$key"
  fi
done

# Update the path based on the WebSocket version if available
if [[ -n "$websocket_version" ]]; then
  yq eval "(.spec.path |= sub(\"/emissary-ingress/[^/]+\", \"/emissary-ingress/$websocket_version\"))" -i "$websocket_file"
  echo "Updated emissary-ingress paths to version $websocket_version"
fi

echo "YAML update complete."