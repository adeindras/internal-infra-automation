// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.
@Library('aurora-migration-shared-lib@rds-aurora-migration-dev') _

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: envList, description: "Target environment name", name: "TARGET_ENVIRONMENT_NAME"),
            choice(choices: ["provisioned","serverless"],description: "Type of RDS Aaurora to Provision", name: 'AURORA_TYPE' ),
            string(description: "Target RDS IaC directory. Relative to RDS terragrunt dir. e.g justice-shared,justice-shared-pg16", name: 'TARGET_RDS_IAC_DIR', defaultValue: "justice-shared" ),
            booleanParam(name: 'PROMOTE_READ_REPLICA_ONLY', defaultValue: false, description: 'Only do Read Replica promotion. Skips all other stages')


        ])
    ]
)

currentBuild.displayName = "#${BUILD_NUMBER}-${TARGET_ENVIRONMENT_NAME}"

pipeline {
  agent {
    kubernetes {
      yaml '''
          apiVersion: v1
          kind: Pod
          metadata:
            namespace: jenkins
            annotations:
              karpenter.sh/do-not-disrupt: "true"
          spec:
            serviceAccountName: jenkinscd-automation-platform
            securityContext:
              runAsUser: 1000
            nodeSelector:
              karpenter.sh/capacity-type: on-demand
            containers:
              - name: jnlp
                image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.1
                resources:
                  requests:
                    memory: 1Gi
                    cpu: 1000m
                  limits:
                    cpu: 1000m
                    memory: 1Gi
        '''
    }
  }

  environment {
    AWS_CONFIG_FILE="${WORKSPACE}/aws.config"
  }

  stages {
    stage('Skip stages') {
      when { expression { params.TARGET_ENVIRONMENT_NAME == 'blank'} }
      steps {
        echo "Target environment is blank, skipping the rest of the stages..."
      }
    }

    stage('Read Environment Information') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        script {
          def environmentDetails = getEnvironmentDetails(params.TARGET_ENVIRONMENT_NAME)
          env.TARGET_ENVIRONMENT_NAME = params.TARGET_ENVIRONMENT_NAME
          env.AURORA_TYPE   = params.AURORA_TYPE 

          def (customer, project, environment) = params.TARGET_ENVIRONMENT_NAME.split('-')

          env.ENVIRONMENT = environment

          env.CUSTOMER_NAME = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.customerName'
          """).trim()

          env.PROJECT = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.project'
          """).trim()

          // env.ENVIRONMENT = sh (label: 'Set ENVIRONMENT', returnStdout: true, script: """#!/bin/bash
          // echo '$environmentDetails' | jq -r '.environment'
          // """).trim()

          // if (env.TARGET_ENVIRONMENT_NAME == "accelbyte-justice-stage") {
          //   env.ENVIRONMENT = "stage"
          // }

          env.AWS_ACCOUNT = sh (label: 'Set AWS_ACCOUNT', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.account'
          """).trim()

          env.AWS_REGION = sh (label: 'Set AWS_REGION', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.region'
          """).trim()
        }
      }
    }

    stage('Preparation') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        dir('iac') {
          checkout (
                changelog: false,
                poll: false,
                scm: scmGit(
                  branches: [[name: "master"]],
                  browser: bitbucket('git@bitbucket.org:accelbyte/iac.git'),
                  extensions: [cloneOption(noTags: true)],
                  userRemoteConfigs: [[credentialsId: 'bitbucket-repo-read-only', url: 'git@bitbucket.org:accelbyte/iac.git']]
                )
              )
        }

        // Configure Default Role
        sh 'echo "[default]" > aws.config'
        sh 'aws configure set web_identity_token_file $AWS_WEB_IDENTITY_TOKEN_FILE --profile default'
        sh 'aws configure set role_arn $AWS_ROLE_ARN --profile default'

        // Configure Automation Platform Role
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set role_arn arn:aws:iam::${env.AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform"
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set source_profile default"

        // Configure Automation Platform Terraform Role
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set role_arn arn:aws:iam::${env.AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform-terraform"
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set source_profile ${TARGET_ENVIRONMENT_NAME}-automation"

        // Set AWS profile to target environment profile from now on.
        script {
          env.AWS_PROFILE = "${TARGET_ENVIRONMENT_NAME}-automation-terraform"

        }
        // Configure kubeconfig
        sh "aws eks --region ${env.AWS_REGION} --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}"

        // Set common vars
        script {
            env.TARGET_ENV_IAC_DIR = "$WORKSPACE/iac/live/$AWS_ACCOUNT/$CUSTOMER_NAME/$PROJECT/$AWS_REGION/$ENVIRONMENT"
            env.TARGET_ENV_MANIFESTS_DIR = "$WORKSPACE/iac/manifests/clusters/$CUSTOMER_NAME/$PROJECT/$AWS_REGION/$ENVIRONMENT"
            env.TARGET_ENV_DEPLOYMENTS_DIR = "$WORKSPACE/deployments/$CUSTOMER_NAME/$PROJECT/$ENVIRONMENT"
            env.TARGET_RDS_IAC_DIR = params.TARGET_RDS_IAC_DIR
            

            env.MAINTENANCE_DIR = "$WORKSPACE/hosting/rollout/Q2-2025/rds-aurora-migration"
            env.MAINTENANCE_TEMPLATES_DIR = "$WORKSPACE/hosting/rollout/Q2-2025/rds-aurora-migration/templates"

        }

        dir("$TARGET_ENV_IAC_DIR/rds_aurora/$AURORA_TYPE/$TARGET_RDS_IAC_DIR"){

            script {

                env.AURORA_CLUSTER_PARAMS_GROUP = runTerragruntOutput("db_cluster_parameter_group_id") 
                env.AURORA_SUBNET_GROUP = runTerragruntOutput("db_subnet_group_name")
                env.AURORA_SG_ID = runTerragruntOutput("security_group_id")
            }
        }

        dir("$TARGET_ENV_IAC_DIR/rds/$TARGET_RDS_IAC_DIR"){

            script {
                env.SOURCE_RDS_ARN = runTerragruntOutput("db_instance_arn")
                env.SOURCE_RDS_ID = sh (returnStdout: true, script: """#!/bin/bash
                echo "$SOURCE_RDS_ARN" | cut -d':' -f7
                """).trim()
            }
        }

        dir("$TARGET_ENV_IAC_DIR"){

            script {

                def tgManifest = "rds/$TARGET_RDS_IAC_DIR/terragrunt.hcl"
                def tgManifestTemp = "rds/$TARGET_RDS_IAC_DIR/terragrunt.tmp.hcl"


                sh "cp '$tgManifest' '$tgManifestTemp'"
                sh "sed -i '/[[:space:]]*inputs[[:space:]]*=/c\\ inputs {' $tgManifestTemp"

                def serviceGroup = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifestTemp' | hcledit attribute get inputs.service_group
                """).trim()

                if(serviceGroup.contains("local.")){
                  serviceGroup = sh (returnStdout: true, script: """#!/bin/bash
                  cat '$tgManifestTemp' | hcledit attribute get locals.service_group
                  """).trim()
                }

                env.SERVICE_GROUP = serviceGroup

                env.AURORA_INSTANCE_ID = "rds-aurora-$CUSTOMER_NAME-$PROJECT-$ENVIRONMENT-$SERVICE_GROUP"
                env.AURORA_CLUSTER_ID = "$AURORA_INSTANCE_ID-cluster"

            }
        }

      }
    }

    stage('Create Aurora Read Replica') {
      when { expression { params.PROMOTE_READ_REPLICA_ONLY != true } }
      steps {

        dir('deployments') {
        checkout scmGit(
            branches: [[name: '*/master']],
            extensions: [ cloneOption(shallow: true,noTags: true) ],
            userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                url: 'git@bitbucket.org:accelbyte/deployments.git']])
        }



        dir("$TARGET_ENV_IAC_DIR/rds/$TARGET_RDS_IAC_DIR"){

            script {



                def rdsInstanceType = sh (returnStdout: true, script: """#!/bin/bash
                aws rds describe-db-instances --db-instance-identifier $SOURCE_RDS_ID --query 'DBInstances[0].DBInstanceClass' --output text --no-cli-pager
                """).trim()

                def rdsEngineVer = sh (returnStdout: true, script: """#!/bin/bash
                aws rds describe-db-instances --db-instance-identifier $SOURCE_RDS_ID --query 'DBInstances[0].EngineVersion' --output text --no-cli-pager
                """).trim()

                def auroraInstance = env.AURORA_TYPE == "provisioned" ? aurora.convertRdsToAuroraProvisioned(rdsInstanceType) : aurora.convertRdsToAuroraServerlessACU(rdsInstanceType)

                if (env.AURORA_TYPE == "provisioned"){

                sh "aws rds create-db-cluster  \
                    --db-cluster-identifier $AURORA_CLUSTER_ID \
                    --db-cluster-parameter-group-name $AURORA_CLUSTER_PARAMS_GROUP \
                    --db-subnet-group-name $AURORA_SUBNET_GROUP \
                    --vpc-security-group-ids $AURORA_SG_ID \
                    --engine aurora-postgresql \
                    --engine-version  $rdsEngineVer \
                    --replication-source-identifier $SOURCE_RDS_ARN \
                    --storage-encrypted"
                } else {

                  sh "aws rds create-db-cluster  \
                    --db-cluster-identifier $AURORA_CLUSTER_ID \
                    --db-cluster-parameter-group-name $AURORA_CLUSTER_PARAMS_GROUP \
                    --db-subnet-group-name $AURORA_SUBNET_GROUP \
                    --vpc-security-group-ids $AURORA_SG_ID \
                    --engine aurora-postgresql \
                    --engine-version  $rdsEngineVer \
                    --replication-source-identifier $SOURCE_RDS_ARN \
                    --serverless-v2-scaling-configuration MinCapacity=${auroraInstance.min},MaxCapacity=${auroraInstance.max} \
                    --storage-encrypted"

                  auroraInstance = "db.serverless"
                }

                sh "aws rds create-db-instance \
                    --db-cluster-identifier $AURORA_CLUSTER_ID \
                    --db-instance-class $auroraInstance \
                    --db-instance-identifier $AURORA_INSTANCE_ID \
                    --enable-performance-insights \
                    --engine aurora-postgresql"


                
            }
     
        }
      }
    }

    stage('Promote Read Replica') {
      steps {
        dir("$TARGET_ENV_IAC_DIR/rds/$TARGET_RDS_IAC_DIR"){

            script {

                def rdsInstanceR53Address = runTerragruntOutput("dns_record")
                def rdsInstanceAddress = runTerragruntOutput("db_instance_address")

                def auroraWriterEp = sh (returnStdout: true, script: """#!/bin/bash
                aws rds describe-db-clusters --db-cluster-identifier $AURORA_CLUSTER_ID --query 'DBClusters[0].Endpoint' --output text --no-cli-pager
                """).trim()

                def sourceRdsSGId = sh (returnStdout: true, script: """#!/bin/bash
                aws rds describe-db-instances \
                --db-instance-identifier $SOURCE_RDS_ID  \
                --query "DBInstances[0].VpcSecurityGroups[*].VpcSecurityGroupId" \
                --output text --no-cli-pager
                """).trim()


               timeout(time: 90, unit: 'MINUTES') {
                    input(
                        id: 'userInputBlockSG', 
                        message: 'Proceed to block the access to source RDS? This will cause DOWNTIME/OUTAGE.', 
                    )
                }

                //Block SG Access to source RDS instance

                sh "aws ec2 describe-security-groups --group-ids $sourceRdsSGId --output json > sg_backup.json"
                archiveArtifacts artifacts: 'sg_backup.json', fingerprint: true

                sh """
                # Block all access by revoking all inbound rules
                aws ec2 revoke-security-group-ingress --group-id $sourceRdsSGId --ip-permissions "\$(aws ec2 describe-security-groups --group-ids $sourceRdsSGId --query 'SecurityGroups[0].IpPermissions' --output json)" --no-cli-pager
                """

                //Restore SG Access
                // sh """
                // # Restore the original security group rules
                // cat sg_backup.json | jq -c '.SecurityGroups[0].IpPermissions[]' | while read -r obj; do
                //   aws ec2 authorize-security-group-ingress \
                //   --group-id "$sourceRdsSGId" \
                //   --ip-permissions "\$obj" \
                //   --no-cli-pager
                // done
                // """

                timeout(time: 45, unit: 'MINUTES') {
                    input(
                        id: 'userInputPromote', 
                        message: 'Make sure replica lag is minimal before promotion. Proceed with promotion? REMINDER: Services are currently in downtime/outage period.', 
                    )
                }

                sh "aws rds promote-read-replica-db-cluster \
                    --db-cluster-identifier $AURORA_CLUSTER_ID"

            }
     
        }
      }
    }

    stage('Import TF State & Apply IaC') {
      steps {
        dir("$TARGET_ENV_IAC_DIR/rds_aurora/$AURORA_TYPE/$TARGET_RDS_IAC_DIR"){

            script {
              runTerragrunt("import 'module.db.aws_rds_cluster.this[0]' $AURORA_CLUSTER_ID")
              runTerragrunt("import 'module.db.aws_rds_cluster_instance.this[\"one\"]' $AURORA_INSTANCE_ID")

              runTerragrunt("plan -out=plan.out")

              timeout(time: 45, unit: 'MINUTES') {
                  input(
                      id: 'userInputTG', 
                      message: 'Please review the plan output before proceeding. Apply the changes?', 
                  )
              }
          
              runTerragrunt("apply -auto-approve plan.out")
            }
     
        }
      }
    }

  }
}

void runTerragrunt(String command) {
  sh "tfenv install"
  sh "AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt ${command}"
}

def runTerragruntOutput(String outputName) {
  sh "tfenv install"
  output = sh (returnStdout: true, script: """#!/bin/bash
                AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt run --experiment cli-redesign --dependency-fetch-output-from-state output ${outputName}
                """).trim()
  return output
}

def getEnvironmentList() {
    envData = []
    withCredentials([string(credentialsId: "AppScriptEndpointUrl", variable: 'appScriptEndpointUrl')]) {
        def get = new URL(appScriptEndpointUrl + "?q=sharedEnvLists").openConnection();
        def getRC = get.getResponseCode();
        println(getRC);
        if(getRC.equals(200)) {
            def jsonSlurper = new JsonSlurper()
            def reply = get.getInputStream().getText()
            def replyMap = jsonSlurper.parseText(reply)
            envData = replyMap
        }
    }

    if (!envData.find()) {
        envData.push("Error getting environment list data")
    } else {
        return envData
    }
}

void getEnvironmentDetails(envName) {
  def eksClusters = ""
  withCredentials([string(credentialsId: "db-inventory-api-key", variable: 'DB_INVENTORY_API_KEY')]) {
      eksClusters = sh (label: 'Download environment data', returnStdout: true, script: '''#!/bin/bash
      curl -s -XGET -H 'accept: application/json' -H "x-api-key: $DB_INVENTORY_API_KEY" 'https://dbinventory-api.dev.hosting.accelbyte.io/listResourcesByType?ResourceType=EKS'
      ''').trim()
  }

  def environmentDetail = sh (label: 'Get environment details', returnStdout: true, script: """#!/bin/bash
  echo '$eksClusters' | jq '.resources[] | select(.name == "$envName") | {name: .name, region: .region, account: .account} + {customerName: .details.Tags[] | select (.Key == "customer_name").Value} + {project: .details.Tags[] | select (.Key == "project").Value} + {environment: .details.Tags[] | select (.Key == "environment").Value}'
  """).trim()

  return environmentDetail
}
