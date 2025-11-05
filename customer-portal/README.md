# Customer Portal Deployment Automation

Contains pipelines and supporting scripts to deploy customer-portal applications to an ECS cluster.

## Jenkins Worker Pod configuration

[./podTemplate.yaml](./podTemplate.yaml) contains the template of the pods that will be created when any of the Job is running.

At the moment, we are using the image originally used for UAT automation: `268237257165.dkr.ecr.us-east-2.amazonaws.com/uat-provisioner-jenkins-agent:0.0.4`

## AWS Credential Configuration

We are using the IAM role attached to `jenkinscd-automation-platform` `ServiceAccount` to assume the role in the Customer Portal AWS account. The role information is all stored as AWS profiles in [./aws.config](./aws.config). The profile is dynamically selected during execution based on the Job parameters.

## Customer Portal Image Update Automation

Directory: [update_image](./update_image/)

Jenkins folder: [hosting/Customer Portal/Deploy](https://jenkinscd.accelbyte.io/job/hosting/job/Customer%20Portal/job/Deploy/)

Contains pipelines and supporting scripts to automatically update Customer Portal services in development environment with the latest image tag from ECR

## Customer Portal Deployment Automation

Directory: [deploy](./deploy/)

Jenkins folder: [hosting/Customer Portal/Update Image](https://jenkinscd.accelbyte.io/job/hosting/job/Customer%20Portal/job/Update%20Image/)

Contains pipelines and supporting scripts to automatically deploy Customer Portal services using infrastructure codes in the [iac](https://bitbucket.org/accelbyte/iac) repository.
