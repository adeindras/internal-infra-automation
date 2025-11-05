#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

# Available environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"

# I recommend to handle Kubernetes credentials outside of the shell script.
# In Hosting Rollout Pipeline, a kubeconfig file containing the configuration
# for the selected target environment is provided at the default location (.kube/config)

# Sample: get pods
kubectl get pods -A

# Sample: get kustomization
flux get kustomization