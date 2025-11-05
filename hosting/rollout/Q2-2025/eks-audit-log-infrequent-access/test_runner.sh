#!/bin/bash

COMMAND=$1
# COMMAND:
# plan-s3
# apply-s3
# export-data
# recreate-log-group
# reset-eks-audit-log
# check-log-stream

set -euo pipefail

export CUSTOMER_NAME=sandbox
export PROJECT=justice
export ENVIRONMENT=dev
export ENVIRONMENT_NAME=dev
export AWS_ACCOUNT=455912570532
export AWS_REGION=us-east-2
export WORKSPACE=/home/adin.baskoro@accelbyte.net/accelbyte/repositories

case "$COMMAND" in
  "plan-s3") ./apply_s3.sh "plan";;
  "apply-s3") ./apply_s3.sh "--terragrunt-non-interactive apply -auto-approve";;
  "export-data") python3 ./export.py "${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}" "${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}-eks-logs";;
  "recreate-log-group") python3 ./recreate-log-group.py "${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}";;
  "reset-eks-audit-log") python3 ./reset-eks-audit-log.py "${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}";;
  "check-log-stream") python3 ./check-log-stream.py "${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}";;
  *) echo "Invalid command";;
esac
