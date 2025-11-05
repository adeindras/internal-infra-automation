#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

# Available Jenkins environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Working directory: ${WORKSPACE}"

ENVIRONMENT_TERRAGRUNT_ROOT="${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT}"
echo "Terragrunt root directory: ${ENVIRONMENT_TERRAGRUNT_ROOT}"

# Sample: plan unique_id terragrunt
pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/unique_id"

# A tfenv install is recommended, to ensure the correct terraform version is installed
tfenv install

# If we are not using the IRSA role that are attached directly to the pod,
# we need to disable AWS_ROLE_ARN and AWS_WEB_IDENTITY

# I recommend to handle AWS credential management outside of the shell script.
# In Hosting Rollout Pipeline, AWS_PROFILE for the selected target environment is already provided, and can be used as-is by Terragrunt.
AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt plan

popd