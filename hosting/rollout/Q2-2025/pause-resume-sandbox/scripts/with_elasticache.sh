#!/bin/bash

ENVIRONMENT_TERRAGRUNT_ROOT="${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT_NAME}/${REGION_NAME}/${ENVIRONMENT_NAME}"

echo "Chosen action: $ACTION"

if [[ "$ACTION" == "RESUME" ]]; then
  echo "Creating Elasticache cluster..."

  pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/elasticache/justice-shared" || exit

  tfenv install
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt apply --auto-approve

  popd || exit

  echo "Elasticache cluster created"

elif [[ "$ACTION" == "PAUSE" ]]; then
  echo "Destroying Elasticache cluster..."

  pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/elasticache/justice-shared"  || exit

  tfenv install
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt destroy --auto-approve

  popd || exit

  echo "Elasticache cluster destroyed"

else
  echo "Unknown action: $ACTION"
  exit 1
fi