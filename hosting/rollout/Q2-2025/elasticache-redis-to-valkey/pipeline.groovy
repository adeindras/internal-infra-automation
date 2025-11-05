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
            string(name: 'TG_PATH', defaultValue: 'elasticache/justice-shared', description: 'Path to ElastiCache Terragrunt module'),
            booleanParam(name: 'CREATE_PR', defaultValue: true, description: 'Enable this if you want to create a PR with the modified files'),
            booleanParam(name: 'ENFORCE_MULTI_AZ', defaultValue: true, description: 'Enable this to enforce multi-az requirements for Elasticache clusters'),
            booleanParam(name: 'SELFHOSTED', defaultValue: false, description: 'Enable this if the Redis server to be updated is self-hosted'),
            booleanParam(name: 'SELFHOSTED_GENERATE_VALKEY', defaultValue: false, description: 'Enable this if you want to generate self-hosted Valkey manifests and have it pushed to Bitbucket'),
            booleanParam(name: 'SELFHOSTED_RECONCILE', defaultValue: false, description: 'Enable this if you want to reconcile kustomizations'),
            booleanParam(name: 'SELFHOSTED_ENABLE_REPLICATION', defaultValue: false, description: 'Enable this if you want to enable self-hosted Valkey replication to Redis primary'),
            booleanParam(name: 'SELFHOSTED_UPDATE_SSM', defaultValue: false, description: 'Enable this if you want to update the SSM parameters with self-hosted Valkey address'),
            booleanParam(name: 'SELFHOSTED_SYNC_SECRET', defaultValue: false, description: 'Enable this if you want to sync justice secrets for a self-hosted Redis upgrade'),
            booleanParam(name: 'SELFHOSTED_RESTART_DEPLOYMENT', defaultValue: false, description: 'Enable this if you want to restart deployments for a self-hosted Redis upgrade'),
            booleanParam(name: 'SELFHOSTED_DISABLE_REPLICATION', defaultValue: false, description: 'Disable replication from selfhosted Redis to Valkey'),
            booleanParam(name: 'SELFHOSTED_SCALE_DOWN_REDIS', defaultValue: false, description: 'Enable this if you want to scale down self-hosted redis to 0')
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

          env.ENVIRONMENT_NAME = sh (label: 'Set ENVIRONMENT_NAME', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.environmentName'
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
      parallel {
        stage('Checkout iac') {
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
          }
        }

        stage('Checkout deployments') {
          steps {
            dir('deployments') {
              checkout (
                    changelog: false,
                    poll: false,
                    scm: scmGit(
                      branches: [[name: "master"]],
                      browser: bitbucket('https://bitbucket.org/accelbyte/deployments'),
                      extensions: [cloneOption(noTags: true)],
                      userRemoteConfigs: [[credentialsId: 'Bitbucket_Build_EPP', url: 'https://engineering-platform-product@bitbucket.org/accelbyte/deployments.git']]
                    )
                )
            }
          }
        }

        stage('Environment configuration') {
          steps {
            // Configure Default Role
            sh """#!/bin/bash
              echo "[default]" > aws.config
              aws configure set web_identity_token_file \$AWS_WEB_IDENTITY_TOKEN_FILE --profile default
              aws configure set role_arn \$AWS_ROLE_ARN --profile default
              aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set role_arn arn:aws:iam::${AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform
              aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set source_profile default
              aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set role_arn arn:aws:iam::${AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform-terraform
              aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set source_profile ${TARGET_ENVIRONMENT_NAME}-automation
              aws eks --region ${AWS_REGION} --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}
            """

            // Set AWS profile to target environment profile from now on.
            script {
              env.AWS_PROFILE = "${TARGET_ENVIRONMENT_NAME}-automation-terraform"
            }
          }
        }
      }
    }


    stage('Upgrade Elasticache') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == false }
      }

      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
          sh "bash upgrade_redis.sh plan ${params.TG_PATH}"
          input(message: "Continue with apply?")
          sh "bash upgrade_redis.sh apply ${params.TG_PATH}"
        }
      }
    }

    stage('Check existing state') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
      }

      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
          sh "bash upgrade_selfhosted.sh check-redis"
          sh "bash upgrade_selfhosted.sh check-deployment"
        }
      }
    }

    stage('Deploy self-hosted Valkey') {
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_GENERATE_VALKEY == true }
      }

      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
          sh "bash upgrade_selfhosted.sh generate-valkey"
        }
      }
    }

    stage('Commit and Push Self-hosted Valkey changes'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_GENERATE_VALKEY == true }
      }

      steps {
        script {
          BB_BRANCH_NAME = "jenkins-${env.AWS_ACCOUNT}-${TARGET_ENVIRONMENT_NAME}-redis-selfhosted-upgrade-${BUILD_NUMBER}"
          sshagent(['bitbucket-repo-read-only']) {
            dir("${WORKSPACE}/iac/manifests/clusters/${env.CUSTOMER_NAME}/${env.PROJECT}/${env.AWS_REGION}/${env.ENVIRONMENT_NAME}") {
              sh """#!/bin/bash
                set -e
                set -x
                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                git checkout -b ${BB_BRANCH_NAME}
                git config --global user.email "build@accelbyte.net"
                git config --global user.name "Build AccelByte"
                git add sync rbac
                git commit -m "feat: Redis-Valkey Self-hosted Upgrade ${TARGET_ENVIRONMENT_NAME}"
                git remote set-url origin git@bitbucket.org:accelbyte/iac.git
                git push --set-upstream origin ${BB_BRANCH_NAME}
              """
            }
          }
        }
      }
    }

    stage('Commit and Push Elasticache Changes'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == false }
        expression { params.CREATE_PR == true }
      }

      steps {
        script {
          BB_BRANCH_NAME = "jenkins-${env.AWS_ACCOUNT}-${TARGET_ENVIRONMENT_NAME}-redis-upgrade-${BUILD_NUMBER}"
          sshagent(['bitbucket-repo-read-only']) {
            dir("${WORKSPACE}/iac/live/${env.AWS_ACCOUNT}/${env.CUSTOMER_NAME}/${env.PROJECT}/${env.AWS_REGION}/${env.ENVIRONMENT_NAME}") {
              sh """#!/bin/bash
                set -e
                set -x
                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                git checkout -b ${BB_BRANCH_NAME}
                git config --global user.email "build@accelbyte.net"
                git config --global user.name "Build AccelByte"
                git add "${params.TG_PATH}"
                git commit -m "feat: Redis-Valkey Upgrade ${TARGET_ENVIRONMENT_NAME}"
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
        expression { params.CREATE_PR == true }
      }
      steps {
        script {
          prSummary="""
  :: Redis-Valkey Upgrade ${TARGET_ENVIRONMENT_NAME} \n \n
          """
          withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
            def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
            def postData =  [
              title: "feat: Redis-Valkey Upgrade ${TARGET_ENVIRONMENT_NAME}",
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

    stage('Reconcile Valkey'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_RECONCILE == true }
      }

      steps {
          input(message: "Is the PR merged?")
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            retry(count: 3) {
              sh "bash upgrade_selfhosted.sh reconcile-valkey"
            }
          }
      }
    }

    stage('Enable Valkey Replication'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_ENABLE_REPLICATION == true }
      }

      steps {
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            sh "bash upgrade_selfhosted.sh enable-replication"
          }
      }
    }

    stage('Update SSM Parameter'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_UPDATE_SSM == true }
      }

      steps {
          input(message: "Update SSM parameter?")
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            sh "bash upgrade_selfhosted.sh update-ssm"
          }
      }
    }

    stage('Sync Secret'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_SYNC_SECRET == true }
      }

      steps {
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            echo "Secrets to be synced:"
            sh "cat service-secrets"
            sh "bash upgrade_selfhosted.sh sync-secret"
          }
      }
    }

    stage('Restart Deployment'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_RESTART_DEPLOYMENT == true }
      }

      steps {
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            echo "Impacted Deployments:"
            sh "cat deployments"
            input(message: "Restart Justice Deployments?")
            sh "bash upgrade_selfhosted.sh restart-deployment"
          }
      }
    }

    stage('Disable Valkey Replication'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_DISABLE_REPLICATION == true }
      }

      steps {
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            sh "bash upgrade_selfhosted.sh check-replication"
            input(message: "Disable Valkey replication?")
            sh "bash upgrade_selfhosted.sh disable-replication"
          }
      }
    }

    stage('Scale Down Redis'){
      when {
        expression { params.TARGET_ENVIRONMENT_NAME != 'blank' }
        expression { params.SELFHOSTED == true }
        expression { params.SELFHOSTED_SCALE_DOWN_REDIS == true }
      }

      steps {
          input(message: "Continue scaling down Redis?")
          dir("$WORKSPACE/hosting/rollout/Q2-2025/elasticache-redis-to-valkey") {
            sh "bash upgrade_selfhosted.sh scaledown-redis"
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
