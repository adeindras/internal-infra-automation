#!/bin/bash

# Define the YAML file
environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
justice_ns_yaml="${environment_manifest_root}/rbac/namespaces/justice/namespace.yaml"
kustomization_yaml="${environment_manifest_root}/kustomization.yaml"

# Define the expected annotation value
expected_value="5432,6379,27017,9092,9094,9096"

# Patch content to add
patch_to_add=$(cat <<EOF
  - patch: |-
      apiVersion: apps/v1
      kind: Kustomization
      metadata:
        name: patch-disable-ecr-sync
      spec:
        patches:
          - patch: |-
              apiVersion: batch/v1
              kind: CronJob
              metadata:
                name: patch
              spec:
                suspend: true
            target:
              kind: CronJob
              name: ecr-credentials-sync
    target:
      kind: Kustomization
      name: flux
EOF
)

# Annotation check and update
yq_eval=".metadata.annotations[\"config.linkerd.io/skip-outbound-ports\"]"
current_value=$(yq eval "$yq_eval" "$justice_ns_yaml")

if [[ "$current_value" == "null" ]]; then
    yq eval "$yq_eval = \"$expected_value\"" -i "$justice_ns_yaml"
elif [[ "$current_value" =~ ", " ]]; then
    cleaned_value=$(echo "$current_value" | tr -d ' ')
    yq eval "$yq_eval = \"$cleaned_value\"" -i "$justice_ns_yaml"
fi

# Patch check and apply
if grep -q "name: patch-disable-ecr-sync" "$kustomization_yaml"; then
    echo "Patch already exists in $kustomization_yaml. No changes made."
else
    echo "Adding patch to $kustomization_yaml..."
    echo -e "\n$patch_to_add" >> "$kustomization_yaml"
    echo "Patch added successfully!"
fi