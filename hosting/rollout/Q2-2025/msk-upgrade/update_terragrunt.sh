#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

TG_PATH=$1

# Available Jenkins environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Working directory: ${WORKSPACE}"

ENVIRONMENT_TERRAGRUNT_ROOT="${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}"
echo "Terragrunt root directory: ${ENVIRONMENT_TERRAGRUNT_ROOT}"

pushd "${ENVIRONMENT_TERRAGRUNT_ROOT}/$TG_PATH"

tgContent=$(sed 's/inputs *= *{/inputs {/' "terragrunt.hcl")
source=$(hcledit attribute get terraform.source <<< "$tgContent")
updatedSource=$(sed 's/v.*"/v2.4.0"/' <<< "$source")


autoscalingTargetValue=$(hcledit attribute get inputs.storage_autoscaling_target_value <<< "$tgContent")
if [[ $autoscalingTargetValue != "" ]]; then
  if (( $autoscalingTargetValue > 60 )); then
    tgContent=$(hcledit attribute set inputs.storage_autoscaling_target_value '60' <<< "$tgContent")
  fi
else
  tgContent=$(hcledit attribute append inputs.storage_autoscaling_target_value '60' <<< "$tgContent")
fi

autoscalingMaxCapacity=$(hcledit attribute get inputs.storage_autoscaling_max_capacity <<< "$tgContent")
if [[ $autoscalingMaxCapacity != "" ]]; then
  if (( $autoscalingMaxCapacity < 1000 )); then
    tgContent=$(hcledit attribute set inputs.storage_autoscaling_max_capacity '1000' <<< "$tgContent")
  fi
else
  tgContent=$(hcledit attribute append inputs.storage_autoscaling_max_capacity '1000' <<< "$tgContent")
fi

autoscalingDisableScaleIn=$(hcledit attribute get inputs.storage_autoscaling_disable_scale_in <<< "$tgContent")
if [[ $autoscalingDisableScaleIn == "" ]]; then
  tgContent=$(hcledit attribute append inputs.storage_autoscaling_disable_scale_in 'true' <<< "$tgContent")
fi

if [[ $source == *"v0.7.0-4"* ]]; then
  tgContent=$(hcledit attribute append inputs.randomized_secretsmanager_secret_id 'true' <<< "$tgContent")
fi

if [[ $source == *"v0.6.4-4"* ]]; then
  tgContent=$(hcledit attribute append inputs.randomized_secretsmanager_secret_id 'true' <<< "$tgContent")
  tgContent=$(hcledit attribute append inputs.secretsmanager_secret_separator '"+"' <<< "$tgContent")
fi

echo "$tgContent"\
  | hcledit attribute set terraform.source "$updatedSource"\
  | hcledit attribute set inputs.msk_kafka_version '"3.9.x"'\
  | sed 's/inputs *{/inputs = {/' > "terragrunt.hcl"

popd
