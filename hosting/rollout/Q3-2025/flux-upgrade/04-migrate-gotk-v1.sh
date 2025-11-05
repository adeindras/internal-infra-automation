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

# Update GitRepository API version
grep -rl 'apiVersion: kustomize.toolkit' "${envManifestPath}"  | xargs -d '\n' -L1 yq -i '(select(.kind == "GitRepository" and .apiVersion == "source.toolkit.fluxcd.io/*") | .apiVersion) = "source.toolkit.fluxcd.io/v1"'

# Update Kustomization API version
grep -rl 'apiVersion: kustomize.toolkit' "${envManifestPath}"  | xargs -d '\n' -L1 yq -i '(select(.kind == "Kustomization" and .apiVersion == "kustomize.toolkit.fluxcd.io/*") | .apiVersion) = "kustomize.toolkit.fluxcd.io/v1"'

# Add special patch for ESO, because it has a nested Kustomization in it
yq -i '(select(fileIndex==0 and .kind == "Kustomization" and .apiVersion == "kustomize.toolkit.fluxcd.io/*" and .metadata.name == "external-secrets-operator") | .spec.patches) += load("./template/v2.0/apiv1.yaml").patches' "${envManifestPath}/sync/core/external-secrets-operator.yaml"
