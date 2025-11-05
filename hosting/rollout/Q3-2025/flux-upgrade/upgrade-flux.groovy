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
      choice(choices: ['0.38.3', '0.39.0', '0.40.2', '0.41.2', '2.0.1', '2.1.2', '2.2.3', '2.3.0'], description: "Target Flux Version", name: "TARGET_VERSION"),
      string(defaultValue: '', name: 'slackChannel', description: 'Target Slack channel'),
      string(defaultValue: '', name: 'slackThreadTs', description: 'Timestamp of the target slack thread')
    ])
  ]
)

String BB_PR_URL

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
              image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.5
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

          env.ENVIRONMENT_NAME = sh (label: 'Set ENVIRONMENT_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.environment_name'
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

        // Configure kubeconfig
        sh "aws eks --region ${AWS_REGION} --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}"
      }
    }

    stage("Flux upgrade") {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank'} }

      steps {
        script {
          stage("Update to v${TARGET_VERSION}") {
            dir("$WORKSPACE/hosting/rollout/Q3-2025/flux-upgrade") {
              sh "bash ./flux-install.sh ${TARGET_VERSION}"
            }
          }

          stage("v${TARGET_VERSION}: Push & Create PR") {
            dir("iac") {
              script{
                echo "[INFO] Preparing Pull Request"

                def commitMessage = "feat: Upgrade Flux to v${TARGET_VERSION} ${TARGET_ENVIRONMENT_NAME}"
                def branchName = "jenkins/flux-upgrade-${TARGET_VERSION}-${params.TARGET_ENVIRONMENT_NAME}-${env.BUILD_NUMBER}"

                pushChanges(branchName, commitMessage, "manifests/clusters/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}")

                def prSummary= "Flux ${TARGET_VERSION} upgrade in ${TARGET_ENVIRONMENT_NAME}"
                def bitbucketModule = load("$WORKSPACE/hosting/rollout/Q3-2025/flux-upgrade/bitbucket.groovy")
                def prInfo = bitbucketModule.createPR(branchName, commitMessage, prSummary)
                env.BB_PR_URL = prInfo.url
                env.BB_PR_ID = prInfo.id.toString()
                echo "${prInfo.id.toString()}"

                slackMessage = ":check: Flux upgrade v${TARGET_VERSION} Pull Request created. *Please review and merge it!* :bitbucket:<${env.BB_PR_URL}|*PR Link*>"
                sendReminderSlackMessage(params.slackChannel, params.slackThreadTs, slackMessage)

              }
            }
          }

          stage("v${TARGET_VERSION}: Get PR Info") {
            dir("$WORKSPACE/hosting/rollout/Q3-2025/flux-upgrade/scripts") {
              script {
                echo "====: Get PR Info :===="
                withCredentials([usernamePassword(credentialsId: "Bitbucket_Build_EPP", passwordVariable: 'BITBUCKET_PASS', usernameVariable: 'BITBUCKET_USER')]) {
                  sh "./get_pr_info.sh $BITBUCKET_USER:$BITBUCKET_PASS $BB_PR_ID"
                }
              }
            }
          }

          stage("v${TARGET_VERSION}: Reconcile flux-system") {
            dir ("$WORKSPACE/hosting/rollout/Q3-2025/flux-upgrade") {
              // When upgrading to v2, use Flux CLI v 0.41.2 as v1 Kustomization CRD is yet to be installed.
              script {
                if ( ${TARGET_VERSION} == "2.0.1" ) {
                  // Download Flux CLI v0.41.2
                  sh '''
                    if [[ ! -e "0.41.2" || ! -f "0.41.2/flux" ]]; then
                      echo "Downloading Flux v0.41.2"
                      mkdir "0.41.2"
                      curl -L -o - https://github.com/fluxcd/flux2/releases/download/v0.41.2/flux_0.41.2_linux_amd64.tar.gz | tar -C 0.41.2 -xz
                    fi
                  '''
                  sh "./0.41.2/flux reconcile kustomization flux-system --with-source --timeout=10m0s"
                } else {
                  sh "./${TARGET_VERSION}/flux reconcile kustomization flux-system --with-source --timeout=10m0s"
                }

                sh "kubectl get deploy -l app.kubernetes.io/version=v${TARGET_VERSION} -o name -n flux-system  | xargs -L1 kubectl -n flux-system rollout status"
                sh "./${TARGET_VERSION}/flux check"
              }
            }
          }

        }
      }
    }
  }
}

// ────────────────────────────────────────────────
// ───── Helper Functions ─────────────────────────
// ────────────────────────────────────────────────
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

def sendReminderSlackMessage(slackChannel, slackThreadTs, message) {
  // slack-obv-cd-token
  withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
    // POST
    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
    def postData =  [
      channel: "${slackChannel}",
      blocks: [
          [
              type: "section",
              fields: [
                  [
                      type: "mrkdwn",
                      text: "${message}"
                  ]
              ]
          ]
      ],
      thread_ts: "${slackThreadTs}"
    ]
    def jsonPayload = JsonOutput.toJson(postData)
    post.setRequestMethod("POST")
    post.setDoOutput(true)
    post.setRequestProperty("Content-Type", "application/json")
    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
    def postRC = post.getResponseCode();
    println(postRC);
    if(postRC.equals(200) || postRC.equals(201)) {
        println(post.getInputStream().getText())
    }
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
  echo '$eksClusters' | jq '.resources[] | select(.name == "$envName") | {name: .name, region: .region, account: .account} + {customerName: .details.Tags[] | select (.Key == "customer_name").Value} + {project: .details.Tags[] | select (.Key == "project").Value} + {environment: .details.Tags[] | select (.Key == "environment").Value} + {environment_name: .details.Tags[] | select (.Key == "environment_name").Value}'
  """).trim()

  return environmentDetail
}

void pushChanges(String branchName, String commitMessage, String pathspec){
  sshagent(['bitbucket-repo-read-only']) {
    sh """#!/bin/bash
        set -e
        export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

        git checkout -b "$branchName"
        git config --global user.email "build@accelbyte.net"
        git config --global user.name "Build AccelByte"
        git status --short
        git add "$pathspec"
        git commit -m "$commitMessage" || true
        git remote set-url origin git@bitbucket.org:accelbyte/iac.git
        echo "git push --set-upstream origin $branchName"
        git push --set-upstream origin "$branchName"
    """
  }
}
