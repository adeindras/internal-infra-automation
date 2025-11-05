#!/usr/bin/env bash
# Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

source .env
instance_count=1

while [ $instance_count -gt 0 ] ; do
    instance_count=$(
        aws ec2 describe-instances \
        --region ${AWS_REGION} \
        --filters "Name=tag:aws:eks:cluster-name,Values=${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}" "Name=instance-state-name,Values=running" "Name=tag:Name,Values=karpenter.sh*" \
        --query "Reservations[].Instances[].InstanceId" | jq length 
    )
    echo "Waiting for $instance_count instances to be terminated..."
    sleep 10
done