import boto3
import sys
import os

from datetime import datetime, timedelta, timezone

eks_client = boto3.client('eks')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('Usage: reset-eks-audit-log.py cluster-name')
        sys.exit(1)

    aws_region=os.environ['AWS_REGION']
    aws_account=os.environ['AWS_ACCOUNT']

    cluster_name = sys.argv[1]

    ok = True

    try:
        response = eks_client.update_cluster_config(name=cluster_name, logging={ 'clusterLogging': [ { 'types': ['audit'], 'enabled': False } ] })
        status = response['update']['status']
        update_id = response['update']['id']
        
        while True:
            status = response['update']['status']
            if  status == 'Successful':
                print('audit log is successfully disabled')
                break

            if (status == 'Failed' or status == 'Canceled'):
                print('failed to disable audit log')
                ok = False
                break

            response = eks_client.describe_update(name=cluster_name, updateId=update_id)

        if not ok:
            sys.exit(1)

        eks_client.update_cluster_config(name=cluster_name, logging={ 'clusterLogging': [ { 'types': ['audit'], 'enabled': True } ] })

        while True:
            status = response['update']['status']
            if  status == 'Successful':
                print('audit log is successfully enabled')
                break

            if (status == 'Failed' or status == 'Canceled'):
                print('failed to enable audit log')
                ok = False
                break

            response = eks_client.describe_update(name=cluster_name, updateId=update_id)

        if not ok:
            sys.exit(1)

    except Exception as e:
        print(f"failed to reset EKS audit log, {e}")
        sys.exit(1)
