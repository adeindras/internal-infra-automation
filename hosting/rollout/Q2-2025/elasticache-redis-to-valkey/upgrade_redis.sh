#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

COMMAND=$1
TG_PATH=$2

# Available Jenkins environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "Environment Name: ${ENVIRONMENT_NAME}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Working directory: ${WORKSPACE}"

ENVIRONMENT_TERRAGRUNT_ROOT="${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}"
echo "Terragrunt root directory: ${ENVIRONMENT_TERRAGRUNT_ROOT}"

function planChanges {
  # Parameters:
  #   $1: path to ElastiCache Terragrunt module
  echo "---------------------------"
  echo "Checking Terragrunt file..."
  echo "---------------------------"
  echo ""
  tgContent=$(sed 's/inputs *= *{/inputs {/' "$1/terragrunt.hcl")

  customerName=$(hcledit attribute get locals.customer_name <<< "$tgContent")
  if [[  $customerName != "" ]]; then
    echo "✅ customer name exists."
  else
    echo "❌ customer name does not exist, please ensure locals.customer_name exists"
    return
  fi

  projectName=$(hcledit attribute get locals.project_name <<< "$tgContent")
  if [[ $projectName != "" ]]; then
    echo "✅ project name exists."
  else
    echo "❌ project name does not exist, please ensure locals.project_name exists"
    return
  fi

  environmentName=$(hcledit attribute get locals.environment_name <<< "$tgContent")
  if [[ $environmentName != "" ]]; then
    echo "✅ environment name exists"
  else
    echo "❌ environment name does not exist, please ensure locals.environment_name exists"
    return
  fi

  serviceName=$(hcledit attribute get locals.service <<< "$tgContent")
  if [[ $serviceName != "" ]]; then
    echo "✅ Service name exists"
  else
    echo "❌ Service name does not exist, please ensure locals.service exists"
    return
  fi

  automaticFailover=$(hcledit attribute get inputs.automatic_failover_enabled <<< "$tgContent")
  source=$(hcledit attribute get terraform.source <<< "$tgContent")

  if [[ ${ENFORCE_MULTI_AZ} == "true" ]];then
    if [[ $(grep 'v0.39.0"' <<< "$source") ]]; then
      # v0.39.0 has multi-az and automatic failover disabled by default
      if [[ "$automaticFailover" == "" || "$automaticFailover" == "false" ]]; then
        echo "❌ Automatic failover is disabled, please enable multi-az and automatic failover first!"
        exit 1
      fi
    else
      # Other newer versions have multi-az and automatic failover enabled by default
      if [[ "$automaticFailover" == "false" ]]; then
        echo "❌ Automatic failover is disabled, please enable multi-az and automatic failover first!"
        exit 1
      fi
    fi

    echo "✅ Automatic failover and Multi-AZ is enabled"
  fi

  updatedSource=$(sed 's/v0\.39\.0.*"/v1.0.0"/' <<< "$source")
  echo "$tgContent"\
    | hcledit attribute set terraform.source "$updatedSource"\
    | hcledit attribute append inputs.description '"ElastiCache ${local.customer_name}-${local.project_name}-${local.environment_name} ${local.service}"'\
    | hcledit attribute set inputs.engine '"valkey"'\
    | hcledit attribute set inputs.engine_version '"8.0"'\
    | hcledit attribute set inputs.family '"valkey8"'\
    | sed 's/inputs *{/inputs = {/' > "$1/terragrunt.hcl"
  
  echo "-------------------"
  echo "Planning changes..."
  echo "-------------------"
  echo ""
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt init -upgrade 
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt plan -out plan.out
}

function applyChanges {
  echo "-------------------"
  echo "Applying changes..."
  echo "-------------------"
  echo ""
  AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt apply --auto-approve plan.out
}

pushd "$ENVIRONMENT_TERRAGRUNT_ROOT/$TG_PATH"
  tfenv install
  case "$1" in
    plan)
      planChanges "$ENVIRONMENT_TERRAGRUNT_ROOT/$TG_PATH"
      ;;
    apply)
      applyChanges "$ENVIRONMENT_TERRAGRUNT_ROOT/$TG_PATH"
      ;;
    *)
      echo "Unknown command"
      exit 1
      ;;
  esac
popd
