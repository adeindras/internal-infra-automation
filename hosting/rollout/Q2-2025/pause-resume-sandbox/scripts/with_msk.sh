#!/bin/bash

ENVIRONMENT_TERRAGRUNT_ROOT="${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT_NAME}/${REGION_NAME}/${ENVIRONMENT_NAME}"

echo "Chosen action: $ACTION"

if [[ "$ACTION" == "RESUME" ]]; then
  echo "Creating MSK cluster..."

  pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/msk/justice-shared" || exit

  tfenv install
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt apply --auto-approve

  popd || exit

  echo "MSK cluster created"

elif [[ "$ACTION" == "PAUSE" ]]; then
  echo "Destroying MSK cluster..."

  pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/msk/justice-shared" || exit

  tfenv install
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt destroy --auto-approve

  popd || exit

  echo "MSK cluster destroyed"

  aws secretsmanager delete-secret --secret-id AmazonMSK_auth_"${CLUSTER_NAME}"-justice_update --region "${REGION_NAME}" --force-delete-without-recovery

else
  echo "Unknown action: $ACTION"
  exit 1
fi