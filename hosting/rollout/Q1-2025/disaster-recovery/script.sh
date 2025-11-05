#!/bin/bash

# Set default values for parameters
aws_region=""
aws_account_id=""
env=""
output_file=""
isIamAutomationSufficient="false"

# Function to log messages (display only)
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1"
}

# Parse command-line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --region|-r) aws_region="$2"; shift ;;
        --aws-account-id|-id) aws_account_id="$2"; shift ;;
        --environment|-e) env="$2"; shift ;;
        *) log "Unknown parameter passed: $1"; exit 1 ;;
    esac
    shift
done

# Validate required parameters
if [[ -z "$aws_region" || -z "$aws_account_id" || -z "$env" ]]; then
    log "ERROR: Missing required parameters. Usage: ./script.sh --region REGION --aws-account-id ACCOUNT_ID --env ENVIRONMENT"
    exit 1
fi

# Set output file dynamically
timestamp=$(date +%Y-%m-%dT%H:%M:%SZ)
output_file="${env}-db-checker.yaml"

# Function to check IAM automation platform policy
check_iam_automation_platform_policy() {
    local aws_account_id="$1"
    local env="$2"

    log "Checking IAM automation platform policy..."
    statementPolicies=$(cat statement-policy.yaml | yq '.Statement.Action | .[]?' | sort | uniq)

    defaultIamPolicyVersion=$(aws iam get-policy \
        --policy-arn arn:aws:iam::$aws_account_id:policy/$env-automation-platform-terraform \
        --output yaml | yq '.Policy.DefaultVersionId')

    defaultIamPolicies=$(aws iam get-policy-version \
        --policy-arn arn:aws:iam::$aws_account_id:policy/$env-automation-platform-terraform \
        --version-id "$defaultIamPolicyVersion" \
        --query 'PolicyVersion.Document' \
        --output yaml | yq '.Statement[].Action | .[]?' | sort | uniq)

    IFS=$'\n' read -r -d '' -a statementPoliciesArray <<< "$statementPolicies"
    IFS=$'\n' read -r -d '' -a defaultIamPoliciesArray <<< "$defaultIamPolicies"

    missingElements=()
    for action in "${statementPoliciesArray[@]}"; do
        if [[ ! " ${defaultIamPoliciesArray[*]} " =~ " ${action} " ]]; then
            missingElements+=("$action")
        fi
    done

    if [ ${#missingElements[@]} -eq 0 ]; then
        log "INFO: All actions in statementPolicies are present in defaultIamPolicies."
        isIamAutomationSufficient=true
    else
        log "ERROR: Some actions in statementPolicies are missing in defaultIamPolicies: ${missingElements[*]}"
    fi
}

# Function to format table output
print_table() {
		echo "" > DbTableReport.txt
		printf ""
		{
    echo -e "\nRESULT TABLE of $env"
    printf "+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
    printf "| IAM $env-automation-platform-terraform is $isIamAutomationSufficient                                                                                                          |\n"	
		printf "+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
    printf "| %-15s | %-75s | %-22s | %-18s | %-22s | %-20s |\n" "DB Type" "Identifier" "Backup Retention Period" "Daily Backup" "Cross Region Snapshot" "Automatic Failover | Multi AZ"
		# printf "dbType" "Identifier" "Backup Retention Period" "Daily Backup" "Cross Region Snapshot" "Automatic Failover | Multi AZ"
    printf "+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
		

    # Process RDS
    rds_entries=$(yq '.dbType[] | select(has("rds")).rds' "$output_file")
		if [[ $rds_entries ]]; then
			echo "$rds_entries" | yq -r '.[] | [.dbIdentifier, .backup_retention_period, .daily_backup_enabled, .cross_region_snapshot, .automatic_failover, .multi_az] | @tsv' | while IFS=$'\t' read -r dbIdentifier retentionPeriod dailyBackup crossRegion automaticFailover multiAz; do
        printf "| %-15s | %-75s | %-22s | %-19s | %-22s | %-18s | %-8s |\n" \
            "RDS" "$dbIdentifier" "$retentionPeriod" "$dailyBackup" "$crossRegion" "$automaticFailover" "$multiAz"
    	done
		fi

    # Process DocumentDB
    docdb_entries=$(yq '.dbType[] | select(has("docdb")).docdb' "$output_file")
    if [[ $docdb_entries ]]; then
			echo "$docdb_entries" | yq -r '.[] | [.clusterIdentifier, .backup_retention_period, .daily_backup_enabled, .cross_region_snapshot, .automatic_failover, .multi_az] | @tsv' | while IFS=$'\t' read -r clusterIdentifier retentionPeriod dailyBackup crossRegion automaticFailover multiAz; do
					printf "| %-15s | %-75s | %-22s | %-19s | %-22s | %-18s | %-8s |\n" \
							"DocumentDB" "$clusterIdentifier" "$retentionPeriod" "$dailyBackup" "$crossRegion" "$automaticFailover" "$multiAz"
			done
		fi

    # Process ElastiCache
    elasticache_entries=$(yq '.dbType[] | select(has("elasticache")).elasticache' "$output_file")
    if [[ $elasticache_entries ]]; then
			echo "$elasticache_entries" | yq -r '.[] | [.replicationGroupId, .backup_retention_period, .daily_backup_enabled, "N/A", .automatic_failover, .multi_az] | @tsv' | while IFS=$'\t' read -r replicationGroupId retentionPeriod dailyBackup crossRegion automaticFailover multiAz; do
					printf "| %-15s | %-75s | %-22s | %-19s | %-22s | %-18s | %-8s |\n" \
							"ElastiCache" "$replicationGroupId" "$retentionPeriod" "$dailyBackup" "$crossRegion" "$automaticFailover" "$multiAz"
			done
		fi

    printf "+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
		printf ""
		} >> DbTableReport.txt
		echo "" >> DbTableReport.txt
}


# Function to get RDS details
get_rds_details() {
    local db_identifier="$1"
    log "Fetching RDS details for DB identifier: $db_identifier..."
    backup_retention_period=$(aws --region $aws_region rds describe-db-instances --db-instance-identifier "$db_identifier" --query "DBInstances[0].BackupRetentionPeriod" --output text)
    daily_backup_enabled=$(aws --region $aws_region rds describe-db-instances --db-instance-identifier "$db_identifier" --query "DBInstances[0].PreferredBackupWindow" --output text)
    cross_region_snapshot=$(aws --region $aws_region rds describe-db-instances --db-instance-identifier "$db_identifier" --query "DBInstances[0].EnableCrossRegionSnapshotCopy" --output text)
    automatic_failover=$(aws --region $aws_region rds describe-db-instances --db-instance-identifier "$db_identifier" --query "DBInstances[0].MultiAZ" --output text)
    multi_az=$(aws --region $aws_region rds describe-db-instances --db-instance-identifier "$db_identifier" --query "DBInstances[0].MultiAZ" --output text)

    echo "      - dbIdentifier: $db_identifier" >> $output_file
    echo "        backup_retention_period: $backup_retention_period" >> $output_file
    echo "        daily_backup_enabled: $daily_backup_enabled" >> $output_file
    echo "        cross_region_snapshot: $cross_region_snapshot" >> $output_file
    echo "        automatic_failover: $automatic_failover" >> $output_file
    echo "        multi_az: $multi_az" >> $output_file
}

get_docdb_details() {
    local cluster_identifier="$1"
    log "Fetching DocDB details for Cluster Identifier: $cluster_identifier..."
    backup_retention_period=$(aws --region $aws_region docdb describe-db-clusters --db-cluster-identifier "$cluster_identifier" --query "DBClusters[0].BackupRetentionPeriod" --output text)
    daily_backup_enabled=$(aws --region $aws_region docdb describe-db-clusters --db-cluster-identifier "$cluster_identifier" --query "DBClusters[0].PreferredBackupWindow" --output text)
    cross_region_snapshot="N/A by default"
    automatic_failover=$(aws --region $aws_region docdb describe-db-clusters --db-cluster-identifier "$cluster_identifier" --query "DBClusters[0].MultiAZ" --output text)
    multi_az=$(aws --region $aws_region docdb describe-db-clusters --db-cluster-identifier "$cluster_identifier" --query "DBClusters[0].MultiAZ" --output text)

    # Output for DocumentDB in YAML format
    echo "      - clusterIdentifier: $cluster_identifier" >> $output_file
    echo "        backup_retention_period: $backup_retention_period" >> $output_file
    echo "        daily_backup_enabled: $daily_backup_enabled" >> $output_file
    echo "        cross_region_snapshot: $cross_region_snapshot" >> $output_file
    echo "        automatic_failover: $automatic_failover" >> $output_file
    echo "        multi_az: $multi_az" >> $output_file
}

# Function to get ElastiCache Replication Group details
get_elasticache_details() {
    local replication_group_id="$1"
    log "Fetching ElastiCache details for Replication Group ID: $replication_group_id..."
    backup_retention_period=$(aws --region $aws_region elasticache describe-replication-groups --replication-group-id "$replication_group_id" --query "ReplicationGroups[0].SnapshotRetentionLimit" --output text)
    daily_backup_enabled=$(aws --region $aws_region elasticache describe-replication-groups --replication-group-id "$replication_group_id" --query "ReplicationGroups[0].SnapshotWindow" --output text)
    automatic_failover=$(aws --region $aws_region elasticache describe-replication-groups --replication-group-id "$replication_group_id" --query "ReplicationGroups[0].AutomaticFailover" --output text)
    multi_az=$(aws --region $aws_region elasticache describe-replication-groups --replication-group-id "$replication_group_id" --query "ReplicationGroups[0].MultiAZ" --output text)

    # Output for ElastiCache in YAML format
    echo "      - replicationGroupId: $replication_group_id" >> $output_file
    echo "        backup_retention_period: $backup_retention_period" >> $output_file
    echo "        daily_backup_enabled: $daily_backup_enabled" >> $output_file
    echo "        automatic_failover: $automatic_failover" >> $output_file
    echo "        multi_az: $multi_az" >> $output_file
}

# Main script logic
log "Script started with parameters: region=$aws_region, account_id=$aws_account_id, env=$env"
echo "env: $env" > $output_file
echo "date: $timestamp" >> $output_file

# Check IAM automation platform policy
check_iam_automation_platform_policy $aws_account_id $env
echo "iam_policy: $isIamAutomationSufficient" >> $output_file
echo "dbType:" >> $output_file

# Get RDS DB identifiers with status 'available'
rds_db_identifiers=$(aws --region $aws_region rds describe-db-instances \
  --query "DBInstances[?Engine!='docdb' && contains(DBInstanceIdentifier, '$env') && DBInstanceStatus=='available'].DBInstanceIdentifier" \
  --output text)

# Get DocumentDB cluster identifiers with status 'available'
docdb_cluster_identifiers=$(aws --region $aws_region docdb describe-db-clusters \
  --query "DBClusters[?contains(DBClusterIdentifier, '$env') && Status=='available'].DBClusterIdentifier" \
  --output text)

# Get ElastiCache replication group identifiers with status 'available'
elasticache_replication_group_ids=$(aws --region $aws_region elasticache describe-replication-groups \
  --query "ReplicationGroups[?contains(ReplicationGroupId, '$env') && Status=='available'].ReplicationGroupId" \
  --output text)

# RDS Section
if [[ $rds_db_identifiers ]]; then
	log "Fetching RDS DB identifiers..."
	echo "  - rds:" >> $output_file
	for db_identifier in $rds_db_identifiers; do
			get_rds_details "$db_identifier"
	done
else
	log "Not Found RDS DB Identifiers"
fi

# DocumentDB Section
if [[ $docdb_cluster_identifiers ]]; then
	log "Fetching DocDB clusters..."
	echo "  - docdb:" >> $output_file
	for cluster_identifier in $docdb_cluster_identifiers; do
    get_docdb_details "$cluster_identifier"
	done
else
	log "Not found DocDB clusters"
fi

# ElastiCache Section using ReplicationGroups
if [[ $elasticache_replication_group_ids ]]; then
	log "Fetching ElastiCache clusters..."
	echo "  - elasticache:" >> $output_file
	for replication_group_id in $elasticache_replication_group_ids; do
			get_elasticache_details "$replication_group_id"
	done
else
	log "Not Found ElastiCache clusters..."
fi
# Print results as a table
print_table

log "Script execution completed. Output written to $output_file."
