#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

# DocDB cluster
# Step 1: List all resources from AWS DocumentDB clusters
source .env
cluster_list=$(aws docdb describe-db-clusters --query "DBClusters[].DBClusterIdentifier" --output text)

# Step 2: Loop through the output and stop the DocumentDB clusters
for cluster_name in $cluster_list; do
    # Step 3: stop the DocumentDB cluster match with regex rule using aws docdb stop-db-cluster command
    if [[ $cluster_name =~ docdb-cluster-$CUSTOMER_NAME-$PROJECT_NAME-$ENVIRONMENT_NAME-.* ]]; then
        echo "Stopping DocumentDB cluster: $cluster_name"
        aws docdb stop-db-cluster --region ${AWS_REGION} --db-cluster-identifier "$cluster_name" >> result
    else
        echo "Skipping $cluster_name..."
    fi
    # Optional: Add a delay to avoid rate limiting issues
    sleep 5
done

# DocDB elastic cluster
# Step 1: List all resources from AWS Elastic DocumentDB clusters ARNs
elastic_cluster_list_arn=$(aws docdb-elastic list-clusters --query "clusters[].clusterArn" --output text)

# Step 2: Loop through the output and stop the matched Elastic DocumentDB clusters
for elastic_cluster_arn in $elastic_cluster_list_arn; do
    elastic_cluster_name=$(aws docdb-elastic get-cluster --region ${AWS_REGION} --cluster-arn $elastic_cluster_arn --query "cluster.clusterName")
    if [[ $elastic_cluster_name =~ docdb-elastic-$CUSTOMER_NAME-$PROJECT_NAME-$ENVIRONMENT_NAME-.* ]]; then
        echo "Stopping Eleastic DocDB cluster $elastic_cluster_name..."
        aws docdb-elastic stop-cluster --cluster-arn $elastic_cluster_arn >> result
    else
        echo "Skipping $elastic_cluster_name..."
    fi
    # Optional: Add a delay to avoid rate limiting issues
    sleep 5
done

# RDS
# Step 1: List all resources from AWS RDS instances
    instance_list=$(aws rds describe-db-instances --query "DBInstances[].DBInstanceIdentifier" --output text)

    # Step 2: Loop through the output and stop the RDS instances
    for instance_name in $instance_list; do
        # Step 3: stop the RDS instance match with regex rule using aws rds stop-db-instance command
        if [[ $instance_name =~ rds-$CUSTOMER_NAME-$PROJECT_NAME-$ENVIRONMENT_NAME-.* ]]; then
            echo "Stopping RDS instance: $instance_name"
            aws rds stop-db-instance --region ${AWS_REGION} --db-instance-identifier "$instance_name" >> result
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
            echo "Stopping RDS Aurora instance: $aurora_instance_name"
            aws rds stop-db-cluster --region ${AWS_REGION} --db-cluster-identifier "$aurora_instance_name" >> result
        else
            echo "Skipping $instance_name..."
        fi
        # Optional: Add a delay to avoid rate limiting issues
        sleep 5
    done

cat result
rm result