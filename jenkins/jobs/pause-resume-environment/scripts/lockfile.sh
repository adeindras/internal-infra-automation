#!/bin/bash
# Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

source .env
AWS_PAGER="" aws s3api get-object --bucket ${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data --key lockfile.json lockfile.json
checkResult=$?

currentOperation=${OPERATION}
lastOperation=$(cat lockfile.json | jq .lock.lastOperation | tr -d '"');
currentExecTIme=${CURRENT_TIMESTAMP}

if [ $checkResult -eq 0 ]; then
    lastExecTime=$(cat lockfile.json | jq .lock.lastExecutionTimestamp | tr -d '"');
    secondsPassed=$(( $currentExecTIme - $lastExecTime ));
    hoursPassed=$(( $secondsPassed / 3600 ));
    minuresPassed=$(( $secondsPassed / 60 ));

    if (( hoursPassed < 1 )); then
        echo "Previous execution is $lastOperation was done less than an hour ago ($minuresPassed minutes), exiting"
        exit 1
    else
        echo "Previous execution was $lastOperation $hoursPassed hours ago, proceeding $currentOperation..."
        envsubst < internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/lockfile-template.json > lockfile.json
        aws_dir="s3://${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data"
        aws s3 cp lockfile.json "$aws_dir/lockfile.json"
        rm -f lockfile.json
    fi
else
    echo "Lockfile doesn't exist, proceeding..."
    envsubst < internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/lockfile-template.json > lockfile.json
    aws_dir="s3://${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data"
    aws s3 cp lockfile.json "$aws_dir/lockfile.json"
    rm -f lockfile.json
fi