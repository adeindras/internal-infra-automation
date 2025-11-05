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
            booleanParam(name: 'ONLY_PATCH_ANNOTATE', defaultValue: false, description: 'Check this if you want to only suspend ECR cron and annotate Justice namespace')
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
          env.CLUSTER_NAME = params.TARGET_ENVIRONMENT_NAME

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

          TIMESTAMP = sh(returnStdout: true, script: """
              date +'%d%m%Y%H%M'
          """
          ).trim()
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

    stage('Deploy Emissary') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.ONLY_PATCH_ANNOTATE == false } }
      steps {
        dir("${WORKSPACE}/hosting/rollout/Q2-2025/emissary-resource-patch") {
          script {
            def ingressExists = sh(script: "kubectl get ingress -n emissary justice-websocket --ignore-not-found", returnStdout: true).trim()
            if (ingressExists.isEmpty()) {
              echo "[+] Ingress 'justice-websocket' not found. Deploying Emissary..."
              sh "bash scripts/split_emissary.sh"
              sh "bash scripts/add_websocket.sh"

              echo "[+] Patching emissary resources..."
              sh "bash scripts/patch_websocket.sh"

              echo "[+] Patching emissary resources..."
              sh "bash scripts/patch_emissary.sh"
            } else {
              echo "[+] Emissary already deployed, patching..."
              sh "bash scripts/patch_emissary.sh"

              echo "[+] Patching emissary resources..."
              sh "bash scripts/patch_websocket.sh"
            }
          }
        }
      }
    }

    stage('Patch Emissary Mapping'){
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.ONLY_PATCH_ANNOTATE == false } }
      steps {
        dir('deployments'){
          sh """#!/bin/bash
            set +x

            pushd ./ab-automation-scripts/emissary-mapping-patch > /dev/null
            ./main.sh
            popd > /dev/null

          """
        }
      }
    }

    stage('Patch Others') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' || params.ONLY_PATCH_ANNOTATE == true } }
      steps {
        dir("${WORKSPACE}/hosting/rollout/Q2-2025/emissary-resource-patch") {
          sh "bash scripts/patch_others.sh"
        }
        script {
          DEPLOYMENTS_CHANGES_DETECTED = checkGitChanges("deployments")
          IAC_CHANGES_DETECTED = checkGitChanges("iac")
        }
      }
    }

    stage('Push Commit') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        sshagent(['bitbucket-repo-read-only']) {
          withCredentials([
            file(credentialsId: "internal-infra-automation-gcp-serviceaccount", variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
            string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'SLACK_BOT_TOKEN')
          ]) {
            script {
              if (DEPLOYMENTS_CHANGES_DETECTED == 'true') {
                pushGitChanges('deployments')
              }
              if (IAC_CHANGES_DETECTED == 'true') {
                pushGitChanges('iac')
              }
            }
          }
        }
      }
    }

    stage('Create PR') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
          script {
            def createPR = { changeType ->
              def repo = changeType == 'deployments' ? 'deployments' : 'iac'
              def branchName = "${TARGET_ENVIRONMENT_NAME}-emissary-resource-patch-${TIMESTAMP}"
              def prTitle = "feat-${changeType}/(${TARGET_ENVIRONMENT_NAME}): emissary resources patch - ${TIMESTAMP}"

              def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/${repo}/pullrequests").openConnection();
              def postData = [
                title: prTitle,
                source: [ branch: [ name: branchName ] ],
                reviewers: [],
                destination: [ branch: [ name: "master" ] ],
                summary: [ raw: "${TARGET_ENVIRONMENT_NAME}" ],
                close_source_branch: true
              ]
              def jsonPayload = JsonOutput.toJson(postData)
              post.setRequestMethod("POST")
              post.setDoOutput(true)
              post.setRequestProperty("Content-Type", "application/json")
              post.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
              post.getOutputStream().write(jsonPayload.getBytes("UTF-8"))
              def postRC = post.getResponseCode()

              if (postRC == 200 || postRC == 201) {
                def jsonSlurper = new JsonSlurper()
                def response = post.getInputStream().getText()
                def responseMap = jsonSlurper.parseText(response)
                prHtmlLink = responseMap.links.html.href
                println("PR Created: ${prHtmlLink}")
              } else {
                println("Failed to create PR for ${changeType}: HTTP ${postRC}")
              }
            }

            if (DEPLOYMENTS_CHANGES_DETECTED == 'true') {
              createPR('deployments')
            }
            if (IAC_CHANGES_DETECTED == 'true') {
              createPR('iac')
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

def checkGitChanges(directory) {
  return sh(returnStdout: true, script: """
    #!/bin/bash
    set -e
    export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
    cd "${WORKSPACE}/${directory}" || exit 1
    [ \$(git diff --name-only | wc -l) -ge 1 ] && echo "true" || echo "false"
  """).trim()
}

def pushGitChanges(directory) {
  dir(directory) {
    sh """
      #!/bin/bash
      set -e
      export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
      git config --global user.email "build@accelbyte.net"
      git config --global user.name "Build AccelByte"
      
      mkdir -p ~/.ssh && curl https://bitbucket.org/site/ssh >> ~/.ssh/known_hosts
      git remote add originssh git@bitbucket.org:accelbyte/${directory}.git
      
      echo "Changes detected in ${directory}, creating branch..."
      git_branch_name="${TARGET_ENVIRONMENT_NAME}-emissary-resource-patch-${TIMESTAMP}"
      git checkout -b \${git_branch_name}

      git add .
      git commit -m "feat(${TARGET_ENVIRONMENT_NAME}): emissary resource patch - ${TIMESTAMP}"
      git push --set-upstream originssh \${git_branch_name}
    """
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
