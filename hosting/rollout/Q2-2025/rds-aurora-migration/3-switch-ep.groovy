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
            string(description: "Target RDS IaC directory. Relative to RDS terragrunt dir. e.g justice-shared,justice-shared-pg16", name: 'TARGET_RDS_IAC_DIR', defaultValue: "justice-shared" )

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
                  extensions: [cloneOption(shallow: true, noTags: true)],
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

                env.SERVICE_GROUP = serviceGroup.replaceAll(/^"|"$/, '')

                env.AURORA_INSTANCE_ID = "rds-aurora-$CUSTOMER_NAME-$PROJECT-$ENVIRONMENT-$SERVICE_GROUP"
                env.AURORA_CLUSTER_ID = "$AURORA_INSTANCE_ID-cluster"

                //cleanup
                sh "rm '$tgManifestTemp'"


            }
        }

      }
    }

    stage('Switch Endpoint') {
      steps {

        dir('deployments') {
        checkout scmGit(
            branches: [[name: '*/master']],
            extensions: [ cloneOption(shallow: true) ],
            userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                url: 'git@bitbucket.org:accelbyte/deployments.git']])
        }

        dir("$TARGET_ENV_IAC_DIR/rds/$TARGET_RDS_IAC_DIR"){

            script {

                // Replace these patterns
                // "rds-justice.internal.dev.sandbox.accelbyte.io"
                // Old RDS endpoint
                // ${SSM_PATH}/postgres/justice_username
                // ${SSM_PATH}/postgres/justice_password
                // ${SSM_PATH}/postgres/justice_port_str
                // ${SSM_PATH}/postgres/justice_port_int
                // ${SSM_PATH}/postgres/justice_address
                // literal /eks/sandbox/justice/dev/postgres/justice_address no ${SSM_PATH} var
                def ssmServiceGroup = env.SERVICE_GROUP.replace('-', '_')

                sh """
                find '${TARGET_ENV_DEPLOYMENTS_DIR}' -type f -exec sed -i 's#/postgres/justice.*address#/aurora_postgresql/${ssmServiceGroup}_address#g' {} +
                find '${TARGET_ENV_DEPLOYMENTS_DIR}' -type f -exec sed -i 's#/postgres/justice.*username#/aurora_postgresql/${ssmServiceGroup}_username#g' {} +
                find '${TARGET_ENV_DEPLOYMENTS_DIR}' -type f -exec sed -i 's#/postgres/justice.*password#/aurora_postgresql/${ssmServiceGroup}_password#g' {} +
                find '${TARGET_ENV_DEPLOYMENTS_DIR}' -type f -exec sed -i 's#/postgres/justice.*port_str#/aurora_postgresql/${ssmServiceGroup}_port_str#g' {} +
                find '${TARGET_ENV_DEPLOYMENTS_DIR}' -type f -exec sed -i 's#/postgres/justice.*port_int#/aurora_postgresql/${ssmServiceGroup}_port_int#g' {} +
                """

                def rdsInstanceR53Address = runTerragruntOutput("dns_record")
                rdsInstanceR53Address =  rdsInstanceR53Address.replaceAll(/^"|"$/, '')

                def rdsInstanceAddress = runTerragruntOutput("db_instance_address")
                rdsInstanceAddress =  rdsInstanceAddress.replaceAll(/^"|"$/, '')


                def auroraWriterEp = sh (returnStdout: true, script: """#!/bin/bash
                aws rds describe-db-clusters --db-cluster-identifier $AURORA_CLUSTER_ID --query 'DBClusters[0].Endpoint' --output text --no-cli-pager
                """).trim()

                sh "find '$TARGET_ENV_DEPLOYMENTS_DIR' -type f -exec sed -i 's#$rdsInstanceR53Address#$auroraWriterEp#g' {} +"
                sh "find '$TARGET_ENV_DEPLOYMENTS_DIR' -type f -exec sed -i 's#$rdsInstanceAddress#$auroraWriterEp#g' {} +"
                //To-Do: use route53 address not aurora wrtier endpoint
                sh "find '$TARGET_ENV_MANIFESTS_DIR' -type f -exec sed -i 's#$rdsInstanceR53Address#$auroraWriterEp#g' {} +"
                sh "find '$TARGET_ENV_MANIFESTS_DIR' -type f -exec sed -i 's#$rdsInstanceAddress#$auroraWriterEp#g' {} +"

            }
     
        }
      }
    }

    stage('Commit and Create PR') {
      steps {
        dir("$WORKSPACE/iac"){
            script {

                def branchName = "automation-rds-aurora-switch-ep-${TARGET_ENVIRONMENT_NAME}"
                def commitMsg  = "feat: repoint to rds aurora endpoint $TARGET_ENVIRONMENT_NAME"
                def prSummary= "Update config to point to RDS Aurora endpoint ${TARGET_ENVIRONMENT_NAME}"
                def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests"


                git.pushChanges(branchName, commitMsg, env.TARGET_ENV_MANIFESTS_DIR)

                git.createPullRequests(repoUrl, branchName, commitMsg, prSummary)

            }
        }

        dir("$WORKSPACE/deployments"){
            script {

                def branchName = "automation-rds-aurora-switch-ep-${TARGET_ENVIRONMENT_NAME}"
                def commitMsg  = "feat: switch services endpoint to rds aurora $TARGET_ENVIRONMENT_NAME"
                def prSummary= "Switch services endpoint to RDS Aurora endpoint ${TARGET_ENVIRONMENT_NAME}"
                def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests"

                git.pushChanges(branchName, commitMsg, env.TARGET_ENV_DEPLOYMENTS_DIR)

                git.createPullRequests(repoUrl, branchName, commitMsg, prSummary)
            }
        }
      }
    }

    stage("Annotate ExternalSecrets") {
      steps {

        timeout(time: 90, unit: 'MINUTES') {
          input(
            id: 'userInput', 
            message: 'Make sure to merge the PRs and wait for all deployment to be finished before proceeding!!!', 
          )
        }
                
        sh"""
          kubectl annotate externalsecrets.external-secrets.io -A --all force-sync=\$(date +%s) --overwrite
          #Waiting for all secrets to be fetched
          sleep 15
        """
      }
    }

    stage('Reconcile IaC'){

      steps {
        script {
            env.PATH = "$PATH:$PWD"
            sh """
                curl -LO https://github.com/fluxcd/flux2/releases/download/v0.38.3/flux_0.38.3_linux_amd64.tar.gz
                tar -xvzf flux_0.38.3_linux_amd64.tar.gz
                chmod +x flux
            """
            
            sh """
                flux reconcile kustomization -n flux-system flux-system --with-source || true
                flux reconcile kustomization -n flux-system cluster-roles --with-source || true
                flux reconcile kustomization -n flux-system cluster-variables --with-source || true
                flux reconcile kustomization -n flux-system external-secrets-operator || true
                flux reconcile kustomization -n flux-system cluster-secret-store || true
                flux reconcile kustomization -n flux-system cluster-secrets || true
                flux reconcile kustomization -n flux-system storageclass || true
                flux reconcile kustomization -n flux-system flux-volume || true
                flux reconcile kustomization -n flux-system karpenter || true
                flux reconcile kustomization -n flux-system karpenter-templates || true
                flux reconcile kustomization -n flux-system flux || true
                flux reconcile kustomization -n flux-system monitoring || true
                flux reconcile kustomization -n flux-system prom-stack || true
            """
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

