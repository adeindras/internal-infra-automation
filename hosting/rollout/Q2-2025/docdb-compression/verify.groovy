// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: envList, description: "Target environment name", name: "TARGET_ENVIRONMENT_NAME"),
            string (defaultValue: '/eks/sandbox/justice/dev/mongo_endpoint', name: 'MONGO_HOST_SSM_PATH', description: 'Path to Mongo host SSM parameter'),
            string (defaultValue: '/eks/sandbox/justice/dev/mongo_username', name: 'MONGO_USERNAME_SSM_PATH', description: 'Path to Mongo username SSM parameter'),
            string (defaultValue: '/eks/sandbox/justice/dev/mongo_password', name: 'MONGO_PASSWORD_SSM_PATH', description: 'Path to Mongo password SSM parameter')
        ])
    ]
)

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
                image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.2
                resources:
                  requests:
                    memory: 1Gi
                    cpu: 500m
                  limits:
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

          env.CUSTOMER_NAME = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.customerName'
          """).trim()

          env.PROJECT = sh (label: 'Set PROJECT', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.project'
          """).trim()

          env.ENVIRONMENT = sh (label: 'Set ENVIRONMENT', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.environment'
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
        sh "aws eks --region ${AWS_REGION} --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}"
      }
    }

    stage('Get Database Credentials for Shared DocDB') {
      when { 
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
      }
      steps {
        // Get shared docdb parameters, write it to jumpbox/manifests/mongo.env for kustomize to load
        dir("${WORKSPACE}/hosting/rollout/Q2-2025/docdb-compression/jumpbox/manifests") {
          script {
            sh 'set +x'
            sh 'touch mongo.env'

            sh (label: 'get MongoDB host name',
            script: "set +x; echo \"MONGO_HOST=\$(aws ssm get-parameter --with-decryption --name ${params.MONGO_HOST_SSM_PATH} --query 'Parameter.Value' | tr -d '\\\"')\" >> mongo.env")

            sh (label: 'get MongoDB username',
            script: "set +x; echo \"MONGO_USERNAME=\$(aws ssm get-parameter --with-decryption --name ${params.MONGO_USERNAME_SSM_PATH} --query 'Parameter.Value' | tr -d '\\\"')\" >> mongo.env")

            sh (label: 'get MongoDB password',
            script: "set +x; echo \"MONGO_PASSWORD=\$(aws ssm get-parameter --with-decryption --name ${params.MONGO_PASSWORD_SSM_PATH} --query 'Parameter.Value' | tr -d '\\\"')\" >> mongo.env")

            sh 'set -x'
          }
        }
      }
    }

    stage('Run') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/docdb-compression/scripts") {
          sh 'bash verify_compression.sh verification.json'
        }
      }
    }
  }

  post {
    always {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/docdb-compression/scripts") {
          archiveArtifacts artifacts: 'verification.json',
                   allowEmptyArchive: false,
                   fingerprint: true,
                   onlyIfSuccessful: false
        }
      }
    }
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
