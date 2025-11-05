#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
# set -e
source .env
readonly ORIG_CWD="$PWD"
readonly INPUT_DIR=${ORIG_CWD}/input-db-data-validation

aws_dir="s3://${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data/"
## bucket is subject of change

cd ${ORIG_CWD}

#copy all files in PAUSE_RESUME folder in s3 and paste to input folder
aws s3 cp ${aws_dir} ${INPUT_DIR} --recursive