import boto3
import sys
import os

from datetime import datetime, timedelta, timezone

cloudwatch_logs = boto3.client('logs')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('Usage: create-log-group.py cluster-name')
        sys.exit(1)

    aws_region=os.environ['AWS_REGION']
    aws_account=os.environ['AWS_ACCOUNT']

    cluster_name = sys.argv[1]
    log_group_name = f'/aws/eks/{cluster_name}/cluster'

    try:
        response = cloudwatch_logs.describe_log_groups(
                logGroupIdentifiers=[f'arn:aws:logs:{aws_region}:{aws_account}:log-group:/aws/eks/{cluster_name}/cluster']   
                )
        log_group_class = response['logGroups'][0]['logGroupClass']

        print(f'log group found: {log_group_name}, class: {log_group_class}')

        if log_group_class == 'STANDARD':
            print(f'deleting log group {log_group_name}...')
            response = cloudwatch_logs.delete_log_group(
                logGroupName=log_group_name,
            )

            print(f'Recreating log group {log_group_name}...')
            response = cloudwatch_logs.create_log_group(
                logGroupName=log_group_name,
                logGroupClass='INFREQUENT_ACCESS'
            )
        else:
            print('not a STANDARD log group, skipping...')
            sys.exit(1)

    except Exception as e:
        print(f"failed to recreate the log group, {e}")
        sys.exit(1)
