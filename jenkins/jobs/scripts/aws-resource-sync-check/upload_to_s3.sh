#!/bin/bash

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <accountName> randomId e.g. accelbyte-justice-stage 123456"
    exit 1
fi
accountName=$1
executionId=$2
WORKDIR="$PWD"
OUTPUT_DIR="$WORKDIR/output"
OUTPUT_IAC_DIR="${OUTPUT_DIR}/iacinfo"
OUTPUT_FILE_DIRS="${OUTPUT_DIR}/directory_lists"
IAC_REPO_DIR="${OUTPUT_DIR}/iac"

IFS='-' read -r customer project environment <<< "$accountName"
echo "Parsed values:"
echo "  Customer name: $customer"
echo "  Project name: $project"
echo "  Environment grade: $environment"
echo "  AWS Account ID: $AWS_ACCOUNT_ID"
echo "  EKS Cluster Name: $EKS_CLUSTER_NAME"
echo "  AWS Region: $AWS_REGION"

aws_dir="s3://accelbyte-devportal-prod-justice-iac-drift-report/automation/infra-drift-check/${accountName}/${executionId}/"
cd ${OUTPUT_DIR}
ls -la $OUTPUT_IAC_DIR
if [ "$(ls -A "${OUTPUT_IAC_DIR}")" ]; then
    echo "The folder has files."
    aws s3 cp ${OUTPUT_IAC_DIR} ${aws_dir} --recursive
else
    echo "The folder is empty."
fi

if [ "$(ls -A "${OUTPUT_FILE_DIRS}")" ]; then
    echo "The folder has files."
    aws s3 cp ${OUTPUT_FILE_DIRS} ${aws_dir}
else
    echo "The folder is empty."
fi