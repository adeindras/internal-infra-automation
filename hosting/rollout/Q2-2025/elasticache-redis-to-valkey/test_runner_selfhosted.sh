#!/bin/bash

# This is a test runner script targetting AB Dev, to help with the development.

COMMAND=$1
# Commands:
# check-redis
# check-deployment
# generate-valkey
# reconcile-valkey
# check-replication
# update-ssm
# sync-secret
# restart-deployment

set -euo pipefail

export CUSTOMER_NAME=abcdexample
export PROJECT=justice
export ENVIRONMENT=dev
export ENVIRONMENT_NAME=dev2
export AWS_ACCOUNT=342674635073
export AWS_REGION=us-east-2
export WORKSPACE=/home/adin.baskoro@accelbyte.net/accelbyte/repositories

bash upgrade_selfhosted.sh $1
