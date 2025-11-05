# Customer Portal Deployment Automation

Jenkins folder: [hosting/Customer Portal/Deploy](https://jenkinscd.accelbyte.io/job/hosting/job/Customer%20Portal/job/Deploy/)

Contains jobs and supporting scripts to automatically deploy Customer Portal services using infrastructure codes in the [iac](https://bitbucket.org/accelbyte/iac) repository.

## Jenkins Job

### Webhook Trigger

The Jenkins job can be triggered automatically from a specifically crafted BitBucket Pull Request (PR) webhook event. The behavior are as follows:

The webhook endpoint uses a token to authenticate incoming requests. The token is stored in Jenkins credential store, with id: `customermortal-deploy-webhook-token`
1. Pull Request Title

    The title must match the following regular expression: `^deploy: accelbyte-(customerportal|docsportal)-(dev|stg|prod) [0-9]{10}$` to trigger the job. The last 10 numbers are supposed to be used for Unix timestamp. In an unix-like environment, you can use `date +%s` to get the timestamp. At the moment, the timestamp is only used as a part of the identifier of the PR, and nothing more.

    Some examples:

    - `deploy: accelbyte-customerportal-dev 1735199618`
    - `deploy: accelbyte-docsportal-prod 1735199618`

2. Environment name

    - If the environment written in the PR title is `dev`, then the change will be automatically be applied after the PR is merged to the `master` branch.
    - If the environment written in the PR title is `stg` or `prod`, then:
        - A Terragrunt plan will be executed when the PR is created.
        - The changes will be automatically applied after the PR is merged to the `master` branch.

3. Manual Terragrunt Plan

    It is possible to manually trigger a Terragrunt plan by adding the following comment in the PR:

    ```
    :plan
    ```


### Manual Trigger

The job can be triggered manually when required. You might need to restart a service, or to test changes from a different branch.

To manually trigger the job, click `Build with Parameters`. Some interesting parameters to be configured:

1. GIT_REF

   The git reference of the iac repository to be deployed. To use branch name that has `/` in it, use the full git reference e.g `refs/heads/chore/cleanup`

2. ENV

    The target environment. Self explanatory.

3. SERVICE

    Choose `autodetect` to automatically detect changed services from the supplied git reference. Otherwise, you can choose service to be deployed.

4. COMMAND

    The command to be executed.

    - Choose `plan` if you only want to see the changes to be applied.
    - Choose `apply --auto-approve` to actually apply the changes.

5. FORCE_DEPLOY

       Force ECS service to recreate the tasks, even though there's no new task definition revision. This is helpful when we want to restart the applications.

### Changed Services Detection

The deployer job can automatically detect changed services from the selected git reference. There's a [helper script](./get_changed_services.py) that the job calls to achieve this.
When using automatic service detection, all changed services are updated in parallel.

### Notification

All deployment activities are sent to [#ecs-customerportal-notif](https://accelbyte.slack.com/archives/C07UV7PBCHY).


## Helper Scripts

### Get Changed Services

[./get_changed_services.py](./get_changed_services.py) compares `HEAD` with `HEAD~1` of an `iac` repo for any changes to the Customer Portal services.

To run the script:

```
python3 get_changed_services.py
```

The script reads from a `config.json` file, located in the same directory as the script. The config file has to be in the following format:

```json
{
    "iac_repo_path": "<path to the iac repository>",
    "base_path": "<Base path to be checked. All files and directories inside will be checked>"
}
```

Below is a valid example of a `config.json` file:

```json
{
    "iac_repo_path": "/tmp/iac",
    "base_path": "live/742281543583/customerportal/justice/us-east-2/dev/customer-portal/services"
}
```

If executed successfully, the script will print the name of services that are changed in the latest commit:

```
$ python3 get_changed_services.py
cp-portal-app
```

In the deployment job, the `config.json` file is generated on the fly, based on the deployment parameters.
