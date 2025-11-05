import boto3
import argparse
from datetime import datetime
import json
import subprocess


def call_aws_function(client, function_name):
    function = getattr(client, function_name)
    return function()


def filter_data(data, query):
    try:
        json_data = json.dumps(data, indent=2)

        process = subprocess.run(
            ["yq", query], input=json_data, text=True, capture_output=True, check=True
        )

        return json.loads(process.stdout)
    except subprocess.CalledProcessError as e:
        print(f"Error filtering data with yq: {e}")
        return data


def organize_output(service, function_name, data, output_data):
    if service not in output_data:
        output_data[service] = {}

    # Handling DB instances for RDS
    if function_name == "describe_db_instances":
        active_instances = {}

        for instance in data.get("DBInstances", []):
            instance_identifier = instance.get("DBInstanceIdentifier")
            instance_status = instance.get("DBInstanceStatus")

            if instance_status == "available" and "rds" in instance_identifier:
                active_instances[instance_identifier] = True

        for instance_identifier in active_instances:
            if instance_identifier not in output_data[service]:
                output_data[service][instance_identifier] = {"snapshots": []}

    # Handling DB snapshots for RDS
    elif function_name == "describe_db_snapshots":
        for snapshot in data.get("DBSnapshots", []):
            snapshot_identifier = snapshot.get("DBSnapshotIdentifier")
            parent_identifier = snapshot.get("DBInstanceIdentifier")
            snapshot_status = snapshot.get("Status")

            if (
                parent_identifier
                and snapshot_identifier
                and snapshot_status == "available"
                and parent_identifier in output_data[service]
            ):
                output_data[service][parent_identifier]["snapshots"].insert(
                    0, snapshot_identifier
                )

    # Handling DB clusters for DocDB
    elif function_name == "describe_db_clusters":
        active_clusters = {}

        for cluster in data.get("DBClusters", []):
            cluster_identifier = cluster.get("DBClusterIdentifier")
            cluster_status = cluster.get("Status")

            if cluster_status == "available" and cluster_identifier:
                active_clusters[cluster_identifier] = True

        for cluster_identifier in active_clusters:
            if cluster_identifier not in output_data[service]:
                output_data[service][cluster_identifier] = {"snapshots": []}

    # Handling DB cluster snapshots for DocDB
    elif function_name == "describe_db_cluster_snapshots":
        for snapshot in data.get("DBClusterSnapshots", []):
            snapshot_identifier = snapshot.get("DBClusterSnapshotIdentifier")
            parent_identifier = snapshot.get("DBClusterIdentifier")
            snapshot_status = snapshot.get("Status")

            if (
                parent_identifier
                and snapshot_identifier
                and snapshot_status == "available"
                and parent_identifier in output_data[service]
            ):
                output_data[service][parent_identifier]["snapshots"].insert(
                    0, snapshot_identifier
                )

    # Handling Replication groups for ElastiCache
    elif function_name == "describe_replication_groups":
        active_replication_groups = {}

        for replication_group in data.get("ReplicationGroups", []):
            replication_group_identifier = replication_group.get("ReplicationGroupId")
            replication_group_status = replication_group.get("Status")

            if replication_group_status == "available" and replication_group_identifier:
                active_replication_groups[replication_group_identifier] = True

        for replication_group_identifier in active_replication_groups:
            if replication_group_identifier not in output_data[service]:
                output_data[service][replication_group_identifier] = {"snapshots": []}

    # Handling Snapshots for ElastiCache
    elif function_name == "describe_snapshots":
        for snapshot in data.get("Snapshots", []):
            snapshot_identifier = snapshot.get("SnapshotName")
            parent_identifier = snapshot.get("CacheParameterGroupName")
            snapshot_status = snapshot.get("SnapshotStatus")
            if (
                parent_identifier
                and snapshot_identifier
                and snapshot_status == "available"
                and parent_identifier in output_data[service]
            ):
                output_data[service][parent_identifier]["snapshots"].insert(
                    0, snapshot_identifier
                )


def main():
    parser = argparse.ArgumentParser(
        description="Retrieve AWS database and snapshot information."
    )
    parser.add_argument("-e", "--environment", required=True, help="Environment name.")
    parser.add_argument("-r", "--region", required=True, help="AWS region to use.")
    parser.add_argument("-o", "--output", required=True, help="Output file name.")
    parser.add_argument(
        "-c", "--config", required=True, help="Path to configuration JSON file."
    )

    args = parser.parse_args()

    # Load the configuration file
    with open(args.config, "r") as config_file:
        config = json.load(config_file)

    # Initialize AWS clients
    clients = {
        "rds": boto3.client("rds", region_name=args.region),
        "docdb": boto3.client("docdb", region_name=args.region),
        "elasticache": boto3.client("elasticache", region_name=args.region),
    }

    # Collect data
    output_data = {"env": args.environment}

    for entry in config:
        service = entry["service"]
        function_name = entry["function"]
        query = entry.get("result_key")

        if service in clients:
            try:
                data = call_aws_function(clients[service], function_name)
                if query:
                    data = filter_data(data, query)
                organize_output(service, function_name, data, output_data)
            except Exception as e:
                print(f"Error calling {function_name} on {service}: {e}")

    # Write data to output file
    with open(args.output, "w") as output_file:
        json.dump(output_data, output_file, indent=2)

    print(f"Data successfully written to {args.output}")


if __name__ == "__main__":
    main()
