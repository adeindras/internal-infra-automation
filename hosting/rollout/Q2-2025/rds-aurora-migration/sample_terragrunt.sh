#!/bin/bash

set -euo pipefail

ENVIRONMENT_TERRAGRUNT_ROOT="${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT}"

# Available environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Terragrunt root directory: ${ENVIRONMENT_TERRAGRUNT_ROOT}"

# Sample: plan unique_id terragrunt
pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/unique_id"

tfenv install
AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt plan

popd
