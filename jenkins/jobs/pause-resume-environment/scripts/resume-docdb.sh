#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights   Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

# DocDB cluster
# Step 1: List all resources from AWS DocumentDB clusters
source .env
cluster_list=$(aws docdb describe-db-clusters --query "DBClusters[].DBClusterIdentifier" --output text)

# Step 2: Loop through the output and start the DocumentDB clusters
for cluster_name in $cluster_list; do
    # Step 3: start the DocumentDB cluster match with regex rule using aws docdb start-db-cluster command
    if [[ $cluster_name =~ docdb-cluster-$CUSTOMER_NAME-$PROJECT_NAME-$ENVIRONMENT_NAME-.* ]]; then
        echo "Starting DocumentDB cluster: $cluster_name"
        aws docdb start-db-cluster --region ${AWS_REGION} --db-cluster-identifier "$cluster_name" >> result
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
        aws docdb-elastic start-cluster --cluster-arn $elastic_cluster_arn >> result
    else
        echo "Skipping $elastic_cluster_name..."
    fi
    # Optional: Add a delay to avoid rate limiting issues
    sleep 5
done

echo "Script execution completed."

cat result
rm result

