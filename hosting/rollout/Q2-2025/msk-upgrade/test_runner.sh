#!/bin/bash

set -euo pipefail

export CUSTOMER_NAME=sandbox
export PROJECT=justice
export ENVIRONMENT=dev
export ENVIRONMENT_NAME=dev
export AWS_ACCOUNT=455912570532
export AWS_REGION=us-east-2
export WORKSPACE=/home/adin.baskoro@accelbyte.net/accelbyte/repositories

BROKER_LIST=$1

./update_terragrunt.sh "msk/justice-shared"
