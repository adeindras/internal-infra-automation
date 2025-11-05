// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(name: "TARGET_ENVIRONMENT_NAME", choices: envList, description: "Target environment name"),
            choice(name: "CCU_SETUP", choices: ['<1k', '>1k-<20k'], description: "Environment CCU setup. Check here:https://accelbyte.grafana.net/d/de4o6lymdarr4c/infrastructure-capacity?orgId=1&from=now-6h&to=now&timezone=utc&viewPanel=panel-1"),
            booleanParam(name: 'WITH_RECONCILE', defaultValue: false, description: 'Whether to run the pipeline with reconciling'),
            booleanParam(name: 'ONLY_PATCH_ANNOTATE', defaultValue: false, description: 'Check this if you want to only shows the ECR cron status and Justice namespace annotation')
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
                image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.1
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

          env.PROJECT_NAME = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.project'
          """).trim()

          env.ENVIRONMENT_NAME = sh (label: 'Set ENVIRONMENT_NAME', returnStdout: true, script: """#!/bin/bash
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
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set role_arn arn:aws:iam::${AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform"
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set source_profile default"

        // Configure Automation Platform Terraform Role
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set role_arn arn:aws:iam::${AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform-terraform"
        sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set source_profile ${TARGET_ENVIRONMENT_NAME}-automation"

        // Set AWS profile to target environment profile from now on.
        script {
          env.AWS_PROFILE = "${TARGET_ENVIRONMENT_NAME}-automation-terraform"

        }
        // Configure kubeconfig
        sh "aws eks --region ${AWS_REGION} --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}"

      }
    }

    stage('Reconciling') {
      when { expression { return params.WITH_RECONCILE } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/emissary-resource-patch") {
          sh """#!/bin/bash
            flux reconcile ks -n justice deployments-kustomization --with-source || true
            flux reconcile ks -n flux-system flux-system --with-source || true
            flux reconcile ks -n flux-system linkerd --with-source || true
            flux reconcile ks -n flux-system emissary-ingress --with-source || true
            flux reconcile ks -n flux-system emissary-ingress-websocket --with-source || true
          """
        }
      }
    }

    stage('Checking') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/emissary-resource-patch") {
          script {
            def isUnderAGSInfra = sh(
              script: "kubectl get ks -n flux-system ags-infrastructure -o yaml | yq '.status.conditions.[0].type'",
              returnStdout: true
            ).trim()

            if (isUnderAGSInfra == 'Ready') {
              echo "[+] Resources are under ags-infrastructure"
              sh "kubectl get ks -n flux-system ags-infrastructure -o yaml | yq '.status.conditions'"
              echo "[+] Out of scopes — checking only the ECR cron and Justice namespace"

              withEnv(["ONLY_PATCH_ANNOTATE=true"]) {
                sh "bash scripts/check_resources.sh"
              }
            } else {
              echo "[+] Resources are not under ags-infrastructure"
              echo "[+] Checking current resources..."

              sh "bash scripts/check_resources.sh"

              if (!params.ONLY_PATCH_ANNOTATE) {
                echo "[+] ONLY_PATCH_ANNOTATE is false — proceeding with full validation."
                sh "bash scripts/validate_mapping.sh ${params.TARGET_ENVIRONMENT_NAME}"
              } else {
                echo "[+] ONLY_PATCH_ANNOTATE is true — skipping mapping validation."
              }
            }
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
