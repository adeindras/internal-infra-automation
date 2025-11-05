#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights   Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

# RDS
# Step 1: List all resources from AWS RDS instances
source .env
instance_list=$(aws rds describe-db-instances --query "DBInstances[].DBInstanceIdentifier" --output text)

# Step 2: Loop through the output and start the RDS instances
for instance_name in $instance_list; do
    # Step 3: start the RDS instance match with regex rule using aws rds start-db-instance command
    if [[ $instance_name =~ rds-$CUSTOMER_NAME-$PROJECT_NAME-$ENVIRONMENT_NAME-.* ]]; then
        echo "Starting RDS instance: $instance_name"
        aws rds start-db-instance --region ${AWS_REGION} --db-instance-identifier "$instance_name" >> result
    else
        echo "Skipping $instance_name..."
    fi
    # Optional: Add a delay to avoid rate limiting issues
    sleep 5
done

# RDS Aurora
# Step 1: List all resources from AWS RDS instances
    aurora_instance_list=$(aws rds describe-db-clusters --query "DBClusters[].DBClusterIdentifier" --output text)

    # Step 2: Loop through the output and stop the RDS instances
    for aurora_instance_name in $aurora_instance_list; do
        # Step 3: stop the RDS instance match with regex rule using aws rds stop-db-instance command
        if [[ $aurora_instance_name =~ rds-aurora-$CUSTOMER_NAME-$PROJECT_NAME-$ENVIRONMENT_NAME-.* ]]; then
            echo "Starting RDS Aurora instance: $aurora_instance_name"
            aws rds start-db-cluster --region ${AWS_REGION} --db-cluster-identifier "$aurora_instance_name" >> result
        else
            echo "Skipping $instance_name..."
        fi
        # Optional: Add a delay to avoid rate limiting issues
        sleep 5
    done


echo "Script execution completed."

cat result
rm result

