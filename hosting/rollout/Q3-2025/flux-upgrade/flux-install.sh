#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

# Available environment variables
# echo "Customer name: ${CUSTOMER_NAME}"
# echo "Project: ${PROJECT}"
# echo "Environment: ${ENVIRONMENT}"
# echo "Environment Name: ${ENVIRONMENT_NAME}"
# echo "AWS Account ID: ${AWS_ACCOUNT}"
# echo "AWS Region: ${AWS_REGION}"
# echo "Workspace: ${WORKSPACE}"

envManifestPath="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}"

# Download requested flux version
if [[ ! -e "$1" || ! -f "$1/flux" ]]; then 
  echo "Downloading Flux v$1"
  mkdir $1
  curl -L -o - https://github.com/fluxcd/flux2/releases/download/v$1/flux_$1_linux_amd64.tar.gz | tar -C $1 -xz
fi

# Generate install manifest
./$1/flux install --export --namespace=flux-system --toleration-keys=CriticalOnInitOnly > "${envManifestPath}/cluster-system/flux-system/gotk-components.yaml"
