#!/bin/bash

# This is a test runner script targetting AB Dev, to help with the development.

set -euo pipefail

export CUSTOMER_NAME=accelbyte
export PROJECT=justice
export ENVIRONMENT=stage
export ENVIRONMENT_NAME=stage
export AWS_ACCOUNT=342674635073
export AWS_REGION=us-west-2
export WORKSPACE=/home/adin.baskoro@accelbyte.net/accelbyte/repositories

# kubectl config use-context "${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}"

# bash ./04-migrate-gotk-v1.sh
bash ./02-flux-037-apply.sh
