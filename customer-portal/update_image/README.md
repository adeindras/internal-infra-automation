# Customer Portal Image Update Automation

Jenkins folder: [hosting/Customer Portal/Update Image](https://jenkinscd.accelbyte.io/job/hosting/job/Customer%20Portal/job/Update%20Image/)

Contains pipelines and supporting scripts to automatically update Customer Portal services in development environment with the latest image tag from ECR.

## Jenkins Job

### Scheduled Execution

The Jenkins job is executed regularly, with the following cron expression:

```
'H/30 0-13 * * 1-5'
```

The expression above translates to:

- Every 30 minutes, starting at some time between 0 and 30 minutes past the hour
- Between hour 0 and hour 13 UTC
- Every weekday

### Manual Execution

The job can be triggered manually when required. For example, when you need to check for a new image as soon as possible.

To manually trigger the job, click `Build with Parameters`. Some interesting parameters to be configured:

1. AUTO_MERGE

    Whether the pull request should be merged to `master` automatically or not. You might want to disable this when you don't want to automatically deploy the resulting changes.

### Image Update Automation

The job scans ECR repositories of Customer Portal and Docs Portal services for the latest image that contains `master-` in the tag. If a new image is found, the job will update the `iac` code with the latest image tag, and create a pull request with the following title:

```
deploy: accelbyte-customerportal-dev <unix timestamp>
```

Example:

```
deploy: accelbyte-customerportal-dev 1735285141
```

The PR can be automatically merged. This is the default behavior.

### Notification

All deployment activities are sent to [#ecs-customerportal-notif](https://accelbyte.slack.com/archives/C07UV7PBCHY).

## Helper Scripts

### Get Latest ECR Image

[./get_latest_ecr_image.py](./get_latest_ecr_image.py) looks for the latest image tag in Customer Portal and Docs Portal ECR repositories.

To execute the script:

```
python3 get_latest_ecr_image.py <config-file.json>
```

The script loads the mapping of service name to name of the repository from a JSON file. The JSON file are in the following format:

```
{
    "<repository_name>": "<service-name>",
    "<repository_name>": "<service-name>"
}
```

Example:

```
{
  "customer-portal-audit-log-service": "cp-audit-log",
  "customer-portal-login-app": "cp-login-app",
  "customer-portal-app": "cp-portal-app",
  "customer-portal-service-app": "cp-service-app",
  "internal-customer-database-integration": "customer-database",
  "justice-ic-service": "justice-ic-service",
  "justice-kafka-connect-sink-v2": "kafka-connect-sink-v2",
  "justice-metering-service-premium": "metering-service",
  "justice-metering-service-premium-worker": "metering-worker"
}
```

The script requires credentials to access AccelByte production account (144436415367) already configured in the executing machine. `service-name` must matches the directory name containing terragrunt configuration files in the iac repo.

If executed successfully, the output will be something like:

```
customer-portal-audit-log-service:master-22bff87-1727951039
customer-portal-login-app:master-e2c58ee2-1734158327
customer-portal-app:master-eb309b27-1735199041
customer-portal-service-app:master-35cc6b3a-1735267932
internal-customer-database-integration:master-74904e8-1734954549
justice-ic-service:master-5ddd6cd-1734950351
justice-kafka-connect-sink-v2:master-1bf29e1-1724821012
justice-metering-service-premium:master-d6ede2b-1735266322
justice-metering-service-premium-worker:master-d6ede2b-1735266322
accelbyte-docs-api-explorer-app:master-ec2122d-1734678171
accelbyte-docs-service-app:master-ec2122d-1734678160
```
