#!/bin/bash
# Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

source .env
echo "Updating analytics-kafka-connect jaas secret.."

cd iac/live/${AWS_ACCOUNT_ID}/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}/msk/justice-shared

cdEval=$?

if [ $retVal -eq 1 ]; then
    cd iac/live/${AWS_ACCOUNT_ID}/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}/msk/justice-shared-updated
else
    echo "MSK justice-shared folder not found, please check it manually! Exiting.."
    exit 0
fi

terragrunt output > justice-shared-msk-output.txt

ssm_username=$(cat justice-shared-msk-output.txt | grep ssm_sasl_username_path | awk '{ print $3 }' | tr -d '"')
ssm_password=$(cat justice-shared-msk-output.txt | grep ssm_sasl_password_path | awk '{ print $3 }' | tr -d '"')

export AWS_PAGER=""
sasl_username=$(aws ssm get-parameters --names $ssm_username --with-decryption --region us-east-2 --query 'Parameters[0].Value' --output text)
sasl_password=$(aws ssm get-parameters --names $ssm_password --with-decryption --region us-east-2 --query 'Parameters[0].Value' --output text)

cp ${WORKSPACE}/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/analytics-scram-jaas-secret-template.txt analytics-scram-jaas-secret.txt
sed -i "s#<username>#$sasl_username#g" analytics-scram-jaas-secret.txt
sed -i "s#<password>#$sasl_password#g" analytics-scram-jaas-secret.txt
aws ssm put-parameter --name /eks/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/analytics-kafka-connect/connect_sasl_jaas_config --type SecureString --overwrite --value file://analytics-scram-jaas-secret.txt  --region us-east-2
rm analytics-scram-jaas-secret.txt