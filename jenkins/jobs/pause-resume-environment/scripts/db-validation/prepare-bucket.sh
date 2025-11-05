#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
# set -e


prepare_bucket() {
    S3_BUCKET=$(echo "${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data")
    if aws s3api head-bucket --bucket "${S3_BUCKET}" 2>/dev/null; then
        echo "Bucket exist, proceeding..."
    else
        current_path=$(pwd)
        bucket_path=$(echo "iac/live/${AWS_ACCOUNT_ID}/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}/s3_paused_data")
        bb_payload_path=$(echo "${current_path}/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/db-validation/bb-pr-payload-template.json")
        echo "Bucket not found, creating bucket ${S3_BUCKET} ..."
        cp -r ${current_path}/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/s3_paused_data ${bucket_path}
        cd $bucket_path
        terragrunt apply --auto-approve
        cd ..
        git branch ${CUSTOMER_NAME}-pause-environment-bucket
        git checkout ${CUSTOMER_NAME}-pause-environment-bucket
        git add s3_paused_data
        git commit -m "${CUSTOMER_NAME}-${ENVIRONMENT_NAME} : provision bucket for pause/resume integration"
        git push --set-upstream origin ${CUSTOMER_NAME}-pause-environment-bucket
        create_iac_bitbucket_pr ${CUSTOMER_NAME}-pause-environment-bucket $bb_payload_path
    fi
}

create_iac_bitbucket_pr() {
  branch=$1
  readonly bitbucket_api_url="https://bitbucket.org/api/2.0/repositories/accelbyte"

  pr_payload=$(
    jq ".title=\"feat: Pause/Resume: ${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME} - create bucket for paused database data\" |
  .source.branch.name=\"${branch}\"" $2
  )
  echo $pr_payload
  echo "Creating PR ..."
  curl -XPOST -H "Content-Type: application/json" -u "${BB_AUTH_USERNAME}:${BB_AUTH_PASSWORD}" "${bitbucket_api_url}/iac/pullrequests" -d "${pr_payload}" | jq .
}

source .env
prepare_bucket
