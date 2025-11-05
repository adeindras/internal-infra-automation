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
            booleanParam(description: "Create S3 bucket", name: "CREATE_S3_BUCKET", defaultValue: true),
            booleanParam(description: "Export log group logs to S3. Doesn't work with Infrequent Access class", name: "EXPORT_S3", defaultValue: true),
            booleanParam(description: "Recreate log group with Infrequent Access class", name: "RECREATE_LOG_GROUP", defaultValue: true),
            booleanParam(description: "Reset EKS audit log configuration", name: "RESET_EKS_AUDIT_LOG", defaultValue: true),
            booleanParam(description: "Check if log streams exist in the log group", name: "CHECK_LOG_STREAM", defaultValue: true)
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

          env.PROJECT = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
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

          env.AWS_DEFAULT_REGION = env.AWS_REGION

          env.ENVIRONMENT_NAME = sh (label: 'Set ENVIRONMENT_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.environmentName'
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
                  browser: bitbucket('https://bitbucket.org/accelbyte/iac'),
                  extensions: [cloneOption(noTags: true)],
                  userRemoteConfigs: [[credentialsId: 'Bitbucket_Build_EPP', url: 'https://engineering-platform-product@bitbucket.org/accelbyte/iac.git']]
                )
              )
        }

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
      }
    }

    stage('Create S3 Bucket') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.CREATE_S3_BUCKET == true }
      }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/eks-audit-log-infrequent-access") {
        sh "./apply_s3.sh \"plan -out plan.out\""
        input("Apply the plan?")
        sh "./apply_s3.sh \"apply plan.out\""
        }
      }
    }

    stage('Commit and Push new S3 terragrunt file'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.CREATE_S3_BUCKET == true }
      }

      steps {
        script {
          BB_BRANCH_NAME = "jenkins-${env.AWS_ACCOUNT}-${TARGET_ENVIRONMENT_NAME}-eks-auditlog-migration-${BUILD_NUMBER}"
          sshagent(['bitbucket-repo-read-only']) {
            dir("${WORKSPACE}/iac/live/${AWS_ACCOUNT}/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}") {
              sh """#!/bin/bash
                set -e
                set -x
                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                git checkout -b ${BB_BRANCH_NAME}
                git config --global user.email "build@accelbyte.net"
                git config --global user.name "Build AccelByte"
                git add s3-eks-log
                git commit -m "feat: S3 bucket for EKS log ${TARGET_ENVIRONMENT_NAME}"
                git remote set-url origin git@bitbucket.org:accelbyte/iac.git
                git push --set-upstream origin ${BB_BRANCH_NAME}
              """
            }
          }
        }
      }
    }

    stage("Create PR to IAC repository") {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.CREATE_S3_BUCKET == true }
      }
      steps {
        script {
          prSummary="""
  :: EKS Audit Log migration to Infrequent Access ${TARGET_ENVIRONMENT_NAME} \n \n
          """
          withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
            def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
            def postData =  [
              title: "feat: EKS Audit log migration ${TARGET_ENVIRONMENT_NAME}",
              source: [
                branch: [
                  name: "${BB_BRANCH_NAME}"
                ]
              ],
              reviewers: [
                  [
                      uuid: "{6cee0dcd-3d6e-4ef1-8cd0-4ca217ed32d2}" // Adin Baskoro Pratomo
                  ],
                  [
                      uuid: "{f115f536-48bf-42f0-9634-30f53f03ed13}" // Adi Purnomo
                  ],
                  [
                      uuid: "{8f4086ef-41e9-4eb3-80c0-84212c1c7594}" // Fahmi Maghrizal Mochtar
                  ],
                  [
                      uuid: "{3bc5a80a-bb63-40a5-829c-82cbde04c2a3}" // Radian Satria Panigas
                  ],
                  [
                      uuid: "{b0089c2d-a507-4211-bc6f-e23cd1735f7a}" // Muhamad Ar Ghifary
                  ],
                  [
                      uuid: "{a60f808f-4034-49da-89f3-4daf9a2367b6}" // Husni Bakri
                  ]
              ],
              destination: [
                branch: [
                  name: "master"
                ]
              ],
              summary: [
                raw: "${prSummary}"
              ],
              close_source_branch: true
            ]
            def jsonPayload = JsonOutput.toJson(postData)
            println(jsonPayload)
            post.setRequestMethod("POST")
            post.setDoOutput(true)
            post.setRequestProperty("Content-Type", "application/json")
            post.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
            post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
            def postRC = post.getResponseCode();
            println(postRC);
            if(postRC.equals(200) || postRC.equals(201)) {
              def jsonSlurper = new JsonSlurper()
              def reply = post.getInputStream().getText()
              def replyMap = jsonSlurper.parseText(reply)
              prHtmlLink = replyMap.links.html.href
            }
          }
        }
      }
    }

    stage('Install Boto') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
      }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/eks-audit-log-infrequent-access") {
          sh "python3 -m venv .venv"
          sh ". .venv/bin/activate; pip install -r requirements.txt"
        }
      }
    }

    stage('Export CloudWatch logs data to S3') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.EXPORT_S3 == true }
      }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/eks-audit-log-infrequent-access") {
          sh ". .venv/bin/activate; python3 ./export.py ${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME} ${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}-eks-logs"
        }
      }
    }

    stage('Recreate Log Group') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.RECREATE_LOG_GROUP == true }
      }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/eks-audit-log-infrequent-access") {
          sh ". .venv/bin/activate; python3 ./recreate-log-group.py ${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}"
        }
      }
    }

    stage('Reset EKS Audit log') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.RESET_EKS_AUDIT_LOG == true }
      }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/eks-audit-log-infrequent-access") {
          sh ". .venv/bin/activate; python3 ./reset-eks-audit-log.py ${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}"
        }
      }
    }

    stage('Check log stream') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.CHECK_LOG_STREAM == true }
      }
      steps {
        sleep 60 // Wait for new logs to be ingested
        dir("$WORKSPACE/hosting/rollout/Q2-2025/eks-audit-log-infrequent-access") {
          sh ". .venv/bin/activate; python3 ./check-log-stream.py ${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}"
        }
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
  echo '$eksClusters' | jq '.resources[] | select(.name == "$envName") | {name: .name, region: .region, account: .account} + {customerName: .details.Tags[] | select (.Key == "customer_name").Value} + {project: .details.Tags[] | select (.Key == "project").Value} + {environment: .details.Tags[] | select (.Key == "environment").Value} + {environmentName: .details.Tags[] | select (.Key == "environment_name").Value}'
  """).trim()

  return environmentDetail
}
