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

        if len(response['logGroups']) == 0:
            print('no log group found.')
            print('migration is not needed')
            sys.exit(0)

        if (response['logGroups'][0]['logGroupClass']) == 'STANDARD':
            print(f'the log group {response["logGroups"][0]["logGroupName"]} is a STANDARD log group')
            print('migration is needed')
            sys.exit(1)

        response = cloudwatch_logs.describe_log_streams(
            logGroupName=f'/aws/eks/{cluster_name}/cluster',
            orderBy='LastEventTime',
            descending=False,
            limit=1
        )

        if len(response['logStreams']) == 0:
            print('no log stream found.')
            sys.exit(1)

        print(f'log stream is found, last event at: {datetime.fromtimestamp(response["logStreams"][0]["lastEventTimestamp"]/1000, timezone.utc)}')

    except Exception as e:
        print(f"failed to check the log stream, {e}")
        sys.exit(1)
