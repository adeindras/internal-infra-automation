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
            booleanParam(name: 'RUN_TERRAGRUNT_ONLY', defaultValue: false, description: 'If the manifest already provisioned and the PR is merged, check this to only apply the manifest.')

        ])
    ]
)


currentBuild.displayName = "#${BUILD_NUMBER}-${TARGET_ENVIRONMENT_NAME}"
currentBuild.description = "Type: ${AURORA_TYPE}\n\nTarget: ${TARGET_RDS_IAC_DIR}"

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
                    memory: 768Mi
                    cpu: 1000m
                  limits:
                    cpu: 1000m
                    memory: 768Mi
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
        // dir('iac') {
        //   checkout (
        //         changelog: false,
        //         poll: false,
        //         scm: scmGit(
        //           branches: [[name: "$iacBranchName"]],
        //           browser: bitbucket('git@bitbucket.org:accelbyte/iac.git'),
        //           extensions: [cloneOption(noTags: true)],
        //           userRemoteConfigs: [[credentialsId: 'bitbucket-repo-read-only', url: 'git@bitbucket.org:accelbyte/iac.git']]
        //         )
        //       )
            
        // }

        sshagent(['bitbucket-repo-read-only']) {
            sh """
                GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
                git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/iac.git" || true
                chown -R 1000:1000 iac
            """
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

      }
    }

    stage('Create RDS Aurora Manifest') {
      when { expression { params.RUN_TERRAGRUNT_ONLY != true } }
      steps {
        dir("$TARGET_ENV_IAC_DIR"){


            script {
                def tgTemplatesAurora = "$MAINTENANCE_TEMPLATES_DIR/rds-$AURORA_TYPE/terragrunt.hcl"

                def tgManifest = "rds/$TARGET_RDS_IAC_DIR/terragrunt.hcl"
                def tgManifestAuroraDir = "rds_aurora/$AURORA_TYPE/$TARGET_RDS_IAC_DIR"
                def tgManifestAurora = "$tgManifestAuroraDir/terragrunt.hcl"


                sh "mkdir -p '$tgManifestAuroraDir'"
                sh "cp '$tgTemplatesAurora' '$tgManifestAuroraDir'"
                sh "ls -lah '$tgManifestAuroraDir'"

                // Remove equal sign from 'inputs' to make it readable by hcledit
                sh "sed -i '/[[:space:]]*inputs[[:space:]]*=/c\\ inputs {' $tgManifest"
                sh "sed -i '/[[:space:]]*inputs[[:space:]]*=/c\\ inputs {' $tgManifestAurora"
                //To-Do: Remove equal sign from scaling_V2_configuration too
                
                // Read current value
                def gameTag = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get locals.game
                """).trim()

                def serviceTag = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get locals.service
                """).trim()

                // Nullable
                def serviceGroup = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get locals.service_group
                """).trim()

                def serviceGroupInputs = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.service_group
                """).trim()

                def allowedCidrBlocks = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.allowed_cidr_blocks
                """).trim()

                def createCWLogsGroup = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.create_cloudwatch_log_group
                """).trim()

                def cWLogsRetention = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.cloudwatch_log_group_retention_in_days
                """).trim()

                def dependencySubnets = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit block get dependency.additional_subnets
                """).trim()

                def rdsInstanceClass = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.rds_instance_class
                """).trim()
                rdsInstanceClass =  rdsInstanceClass.replaceAll(/^"|"$/, '')

                def rdsEngineVersion = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.rds_engine_version
                """).trim()

                // To-Do: Temporarily set rds.logical_replication to 0 before migration and roll it back to 1 after

                def route53Name = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get inputs.route53_name
                """).trim()

                def depsDNSZone = sh (returnStdout: true, script: """#!/bin/bash
                cat '$tgManifest' | hcledit attribute get dependency.dns_zone.config_path
                """).trim()
                depsDNSZone = depsDNSZone.replaceAll(/^"|"$/, '')

                

                // Replace Aurora TG manifest
                sh "hcledit -u -f '$tgManifestAurora' attribute set locals.game '$gameTag'"
                sh "hcledit -u -f '$tgManifestAurora' attribute set locals.service '$serviceTag'"

                // handle AllowedCidrBlocks
                if(allowedCidrBlocks){
                    sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.allowed_cidr_blocks '$allowedCidrBlocks'"
                }

                if (!dependencySubnets){
                    sh "hcledit -u -f '$tgManifestAurora' block rm dependency.additional_subnets"
                    echo "Remove dependency.additional_subnets"
                }

                if (createCWLogsGroup){
                    sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.create_cloudwatch_log_group '$createCWLogsGroup'"
                    echo "Enable inputs.create_cloudwatch_log_group"
                }

                sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.cloudwatch_log_group_retention_in_days '$cWLogsRetention'"

                sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.service_group '$serviceGroupInputs'"
                sh "hcledit -u -f '$tgManifestAurora' attribute set locals.service_group '$serviceGroupInputs'"
                if(serviceGroupInputs == "local.service_group"){
                    sh "hcledit -u -f '$tgManifestAurora' attribute set locals.service_group '$serviceGroup'"
                }

                if (env.AURORA_TYPE == "provisioned") {
                  def auroraInstance = aurora.convertRdsToAuroraProvisioned(rdsInstanceClass)
                  sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.instance_class '\"$auroraInstance\"'"
                } else {
                  def auroraInstance = aurora.convertRdsToAuroraServerlessACU(rdsInstanceClass)
                  sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.instance_class '\"db.serverless\"'"
                  sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.serverlessv2_scaling_configuration.min_capacity '\"$auroraInstance[0]\"'"
                  sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.serverlessv2_scaling_configuration.max_capacity '\"$auroraInstance[1]\"'"
                }

                sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.engine_version '$rdsEngineVersion'"

                sh "hcledit -u -f '$tgManifestAurora' attribute set dependency.dns_zone.config_path '../$depsDNSZone'"

                def newr53Name = route53Name.replace("rds-", "rds-aurora-")
                sh "hcledit -u -f '$tgManifestAurora' attribute set inputs.route53_name '$newr53Name'"

                sh "sed -i '/[[:space:]]*inputs[[:space:]]*{/c\\ inputs = {' $tgManifestAurora"

                sh "cat '$tgManifestAurora'"
            }
            
        }
      }
    }

    stage('Commit and Create PR') {
      when { expression { params.RUN_TERRAGRUNT_ONLY != true } }
      steps {
        dir("$TARGET_ENV_IAC_DIR"){
            script {

                def tgManifestAurora = "rds_aurora/$AURORA_TYPE/$TARGET_RDS_IAC_DIR/terragrunt.hcl"
                def branchName = "automation-rds-aurora-migration-${TARGET_ENVIRONMENT_NAME}"
                def commitMsg  = "feat: add rds aurora $TARGET_ENVIRONMENT_NAME"
                def prSummary= "Provision RDS Aurora ${TARGET_ENVIRONMENT_NAME}"
                def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests"

                git.pushChanges(branchName, commitMsg, tgManifestAurora)

                git.createPullRequests(repoUrl,branchName, commitMsg, prSummary)

                timeout(time: 60, unit: 'MINUTES') {
                  input(
                    id: 'userInput', 
                    message: 'Make sure to merge the PR before proceeding!', 
                  )
                }
            }
        }
      }
    }

    stage('Provision RDS Aurora Dependencies') {
      steps {
        dir("$TARGET_ENV_IAC_DIR/rds_aurora/$AURORA_TYPE/$TARGET_RDS_IAC_DIR"){


            sh "terragrunt hclfmt || true"

            runTerragrunt("plan -target='module.db.aws_security_group.this[0]' \
                -target='module.db.aws_db_subnet_group.this[0]' \
                -target='module.db.aws_rds_cluster_parameter_group.this[0]' \
                -target='module.db.aws_security_group_rule.this[\"ingress\"]' -out=plan.out")

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

void runTerragrunt(String command) {
  sh "tfenv install"
  sh "AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt ${command}"
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
