#!/bin/bash

set -euo pipefail

# Available environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"

# Sample: get pods
kubectl get pods -A

# Sample: get kustomization
flux get kustomization
