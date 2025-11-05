#!/usr/bin/env bash
# Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

source .env

list_of_hr="$(kubectl get helmrelease -n extend-${CUSTOMER_NAME}-custom-service | grep True | awk '{print $1;}')"
while IFS= read -r hr; do
    flux suspend hr $hr -n extend-${CUSTOMER_NAME}-custom-service
    flux resume hr $hr -n extend-${CUSTOMER_NAME}-custom-service
done <<< "$list_of_hr"