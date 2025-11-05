#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
# set -e
source .env
readonly ORIG_CWD="$PWD"
readonly OUTPUT_DIR=${ORIG_CWD}/output-db-check

aws_dir="s3://${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data/"
## bucket is subject of change

cd ${ORIG_CWD}

#copy all items in output folder and upload to s3
aws s3 cp ${OUTPUT_DIR} ${aws_dir} --recursive