#!/bin/bash

# This is a test runner script targetting AB Stage, to help with the development.

set -euo pipefail

export CUSTOMER_NAME=accelbyte
export PROJECT=justice
export ENVIRONMENT=stage
export AWS_ACCOUNT=342674635073
export AWS_REGION=us-west-2
export WORKSPACE=/home/adin.baskoro@accelbyte.net/accelbyte/repositories

bash upgrade_redis.sh plan elasticache/justice-shared
bash upgrade_redis.sh apply elasticache/justice-shared
