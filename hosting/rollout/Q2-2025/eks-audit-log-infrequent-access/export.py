import boto3
import sys
import time

from datetime import datetime, timedelta, timezone

# Initialize boto3 clients
cloudwatch_logs = boto3.client('logs')
s3_client = boto3.client('s3')

def export_logs_to_s3(task_name, log_group, s3_bucket, start_time, end_time):
    ok = True
    start_timestamp = int(start_time.timestamp() * 1000)
    end_timestamp = int(end_time.timestamp() * 1000)

    try:
        response = cloudwatch_logs.create_export_task(
            taskName=task_name,
            logGroupName=log_group,
            fromTime=start_timestamp,
            to=end_timestamp,
            destination=s3_bucket,
            destinationPrefix=f'cloudwatch-logs/{log_group}/{start_time.strftime("%Y-%m-%d")}/'
        )
        task_id = response['taskId']
        print(f'Export task created with task ID: {task_id}')

        ok = check_task_status(task_id) 

    except Exception as e:
        print(f'Error creating export task: {e}')
        ok = False

    return ok

def check_task_status(task_id):
    ok = True
    while True:
        try:
            status_response = cloudwatch_logs.describe_export_tasks(taskId=task_id)
            task = status_response['exportTasks'][0]
            status = task['status']['code']

            if status == 'COMPLETED':
                print('Log export task completed successfully.')
                break
            elif status == 'FAILED':
                print('Log export task failed.')
                ok = False
                break
            else:
                print(f'Task in progress, with status {status}. Waiting for completion...')
                time.sleep(10)

        except Exception as e:
            print(f'Error checking task status: {e}')
            ok = False
            break

    return ok

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('Usage: create-log-group.py cluster-name')
        sys.exit(1)

    cluster_name = sys.argv[1]

    log_group_name = f'/aws/eks/{cluster_name}/cluster'
    s3_bucket_name = sys.argv[2]
    end_time = datetime.now(timezone.utc)
    start_time = end_time - timedelta(days=30)

    ok = export_logs_to_s3(f'eks-export-{cluster_name}', log_group_name, s3_bucket_name, start_time, end_time)
    if not ok:
        print("the export is failed.")
        sys.exit(1)
