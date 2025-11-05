// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def userId
def slackThread
def slackChannel

// Set to 'true' for the main pipeline to enable Slack thread creation.
// Set to 'false' for pre/post-flight pipelines to skip Slack notifications.
def isMainPipeline = true

// Please provide the following details to configure this pipeline 
// appropriately based on the intended deployment or task context.
String taskTitle = "Linkerd 2.14 Upgrade"
String taskDesc = "We need to update to Linkerd 2.14 so that we can take advantage of the latest performance improvements, security patches, and new features"
String taskImpact = "Minor disruption due to restart of meshed-workloads. This includes most AGS Services, Extends controller and App, Otel Metric scraper."

// Slack channel ID for sending test notifications.
// Defaults to #my-test-channel.
// Update this value if you'd prefer to use your own testing channel.
String channelForTesting = "C091YBUH465"

// This will sent to #report-infra-changes.
String channelReportInfra = "C017L2M1C3D"

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: envList, description: "Target environment name", name: "TARGET_ENVIRONMENT_NAME"),
            choice(choices: ["2.14","2.15"], description: "Target Linkerd version", name: "TARGET_LINKERD_VERSION"),
            text(description: "Optional. Only set this for external AWS account environment e.g Dreamhaven Stage, Dreamhaven Prod etc", name: 'EXT_ACC_AWS_CREDS', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),
            choice(choices:["all-unauthenticated","audit","all-authenticated"],name: 'AUTHZ_POLICY', description: 'Default authz policy for linkerd-injected namespace. If in doubt, use all-unauthenticated.'),
            booleanParam(name: 'VALIDATION_ONLY', defaultValue: false, description: 'Run only the validation stage.'),
            booleanParam(name: 'SKIP_IAC_UPDATE', defaultValue: false, description: 'Skip the IaC manifest update stage.'),
            booleanParam(name: 'SKIP_PRE_CHECK', defaultValue: false, description: 'Skip the Upgrade pre-check stage.')

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
                image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.2
                resources:
                  requests:
                    memory: 1Gi
                    cpu: 500m
                  limits:
                    cpu: 500m
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

          env.userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
          currentBuild.displayName = "#${BUILD_NUMBER} - ${TARGET_ENVIRONMENT_NAME} - ${env.userId}"

          env.TASK_TITLE = taskTitle
          env.TASK_DESC = taskDesc
          env.TASK_IMPACT = taskImpact
          env.CHANNEL_REPORT_INFRA = channelReportInfra
          env.CHANNEL_FOR_TESTING = channelForTesting
          
          // Pipelines located in the DEVELOPMENT folder (or any non-standard naming) will send notifications to #my-test-channel.
          // Pipelines under the STABLE folder will default to sending notifications #report-infra-changes.
          if (WORKSPACE.contains("DEVELOPMENT")) {
              env.slackChannel = env.CHANNEL_FOR_TESTING
          } else if (WORKSPACE.contains("STABLE")) {
              env.slackChannel = env.CHANNEL_REPORT_INFRA
          } else {
              env.slackChannel = env.CHANNEL_FOR_TESTING
          }


          def environmentDetails = getEnvironmentDetails(params.TARGET_ENVIRONMENT_NAME)
          env.TARGET_ENVIRONMENT_NAME = params.TARGET_ENVIRONMENT_NAME
          env.TARGET_LINKERD_VERSION = params.TARGET_LINKERD_VERSION.trim()

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


          env.IAC_MANIFEST_DIR = "$WORKSPACE/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT}"
          env.IAC_MANIFEST_EXTENDED_DIR = "${IAC_MANIFEST_DIR}/sync/extended" //manifests/clusters/sandbox/justice/us-east-2/dev/sync/extended/
          env.IAC_MANIFEST_NAMESPACES_DIR = "${IAC_MANIFEST_DIR}/rbac/namespaces"//rbac/namespaces/justice/namespace.yaml

          env.DEPLOYMENT_DIR = "$WORKSPACE/deployments/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT}"

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
                  extensions: [cloneOption(noTags: true, shallow: true)],
                  userRemoteConfigs: [[credentialsId: 'bitbucket-repo-read-only', url: 'git@bitbucket.org:accelbyte/iac.git']]
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
          def creds = params.EXT_ACC_AWS_CREDS.split('\n')
          def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = creds.size() >= 3 ? [creds[0], creds[1], creds[2]] : [creds[0], creds[1], null]

          if (awsAccessKeyId != "export AWS_ACCESS_KEY_ID=asd" ){
              env.AWS_ACCESS_KEY_ID = awsAccessKeyId.replaceAll('"', '').split('=')[1]
              env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey.replaceAll('"', '').split('=')[1]
              if (awsSessionToken != null) env.AWS_SESSION_TOKEN = awsSessionToken.replaceAll('"', '').split('=')[1]
              sh 'aws sts get-caller-identity --no-cli-pager'
          }

        }
        // Configure kubeconfig
        sh "aws eks --region ${env.AWS_REGION}  --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}"

      }
    }

    stage('Sending Slack Notification') {
      when { expression { return shouldSendSlackNotif(isMainPipeline) } }
      steps {
        script {
          sendSlackNotification()
        }
      }
    }

    stage('Upgrade Pre-check') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.VALIDATION_ONLY != true && params.SKIP_PRE_CHECK != true } }
      steps {

        dir("$WORKSPACE/hosting/rollout/Q3-2025/linkerd-upgrade") {
          script {
            sh "bash ./cleanup-linkerd-viz.sh"

          }
        }

        dir("$IAC_MANIFEST_DIR") {
          script {



            env.PATH = "$PATH:$PWD/.linkerd2/bin"
            sh "curl -sL https://run.linkerd.io/install | LINKERD2_VERSION=stable-2.13.5 sh"
            sh "curl -sL https://linkerd.github.io/linkerd-smi/install | sh"

            //For internal env:
            //Delete linkerd-viz namespace. Remove finalizer as previous linkerd-viz cleanup left out bunch of uncleaned CR
            // sh "kubectl patch namespace linkerd-viz -p '{"spec":{"finalizers":[]}}' --type=merge"
            //For customer env, linkerd-viz workload still running normally
            sh "linkerd check || true"
            //To-Do: Target all namespace where meshed-workloads exist
            sh "linkerd check --proxy --namespace justice || true"

            successJobReply( "✅ Pre-check is done. Review the pre-check report in :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )

            timeout(time: 60, unit: 'MINUTES') {
              input(
                id: 'userInput', 
                message: 'Please check the pre-check result above before preceeding. Ignore: linkerd-viz check', 
              )
            }

          }
        }
      }
    }

    stage('Update IaC Manifest') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.VALIDATION_ONLY != true && params.SKIP_IAC_UPDATE != true } }
      steps {
        dir("$IAC_MANIFEST_DIR") {
          script {
            
            def isAgsInfraTiering = false
            def tier
            if (fileExists("${IAC_MANIFEST_EXTENDED_DIR}/ags-infrastructure.yaml")) {

              def yamlText = readFile("${IAC_MANIFEST_EXTENDED_DIR}/ags-infrastructure.yaml")

              // Split into individual YAML documents (filter out empty ones)
              def docs = yamlText.split('(?m)^---\\s*$').findAll { it.trim() }

              isAgsInfraTiering = docs.any { docText ->
                  def doc = readYaml text: docText
                  doc instanceof Map &&
                      doc.kind == 'Kustomization' &&
                      doc.metadata?.name == 'ags-infrastructure'
              }
              tier = docs.find { docText ->
                  def doc = readYaml text: docText
                  doc instanceof Map &&
                      doc.kind == 'Kustomization' &&
                      doc.metadata?.name == 'ags-infrastructure'
              }?.with { docText ->
                  def doc = readYaml text: docText
                  doc?.spec?.path
              }
            }


            if (isAgsInfraTiering) {
                echo 'This environment use AGS Infrastructure tiering..'
                echo "${tier}"

                //To-Do: Need to handle multi yaml in a file with yq
                // def refBranchExists = sh(
                //   script: "yq 'select(.kind == \"GitRepository\" and .metadata.name == \"ags-infrastructure-platform-repo\") | .spec.ref | has(\"branch\")' '${IAC_MANIFEST_DIR}/sync/core/repo.yaml'",
                //   returnStdout: true
                // ).trim()

                // if (refBranchExists == "true") {
                //   echo "✅ ref.branch exists. Deleting..."
                //   sh "yq 'select(.kind == \"GitRepository\" and .metadata.name == \"ags-infrastructure-platform-repo\") | del(.spec.ref.branch)' -i '${IAC_MANIFEST_DIR}/sync/core/repo.yaml'"

                // }
                sh "yq 'select(.kind == \"GitRepository\" and .metadata.name == \"ags-infrastructure-platform-repo\") .spec.ref.tag = \"v1.1.8\"' -i '${IAC_MANIFEST_DIR}/sync/core/repo.yaml'"
                sh "yq 'select(.kind == \"Kustomization\" and .metadata.name == \"linkerd-jaeger\") .spec.path = \"./manifests/platform/linkerd-jaeger/v2.14.10\"' -i '${IAC_MANIFEST_EXTENDED_DIR}/linkerd.yaml'"


            } else {

                echo 'This environment does not use AGS Infrastructure tiering..'

                def linkerdPath = "${IAC_MANIFEST_EXTENDED_DIR}/linkerd.yaml"

                sh "yq 'select(.kind == \"Kustomization\" and .metadata.name == \"linkerd\") .spec.path = \"./manifests/platform/linkerd/2.14.10/karpenter-cw\"' -i '${linkerdPath}'"
                sh "yq 'select(.kind == \"Kustomization\" and .metadata.name == \"linkerd-jaeger\") .spec.path = \"./manifests/platform/linkerd-jaeger/v2.14.10\"' -i '${linkerdPath}'"

            }

            def defaultPolicy = params.AUTHZ_POLICY

            if (fileExists("${IAC_MANIFEST_NAMESPACES_DIR}/justice/namespace.yaml")) {
              //Annotate manifest for Authz/n enforcement
              sh "yq ' .metadata.annotations[\"config.linkerd.io/default-inbound-policy\"] = \"${defaultPolicy}\" '  -i '${IAC_MANIFEST_NAMESPACES_DIR}/justice/namespace.yaml'"
            } else {
              echo "Namespace is not managed in IaC. Direct patching the namespace will be done in Enforce Authz stage"
            }

            def branchName = "automation-linkerd-upgrade-${TARGET_ENVIRONMENT_NAME}"
            def commitMsg  = "feat: upgrade linkerd to ${TARGET_LINKERD_VERSION} ${TARGET_ENVIRONMENT_NAME}"
            
            pushChanges(branchName, commitMsg, IAC_MANIFEST_DIR)
            
            def prSummary= "Upgrade linkerd to ${TARGET_LINKERD_VERSION} ${TARGET_ENVIRONMENT_NAME} and enforce Auth"
            def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests"

            createPullRequests(repoUrl, branchName, commitMsg, prSummary)

            successJobReply("✅ Pull Request created. Get the PR link in :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )

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

    stage('Upgrade Linkerd Control Plane') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.VALIDATION_ONLY != true } }
      steps {
        dir("$IAC_MANIFEST_DIR") {
          script {

            def versionMapping = getLinkerdVersionMapping()
            def linkerdVersion = env.TARGET_LINKERD_VERSION
            
            if (!versionMapping.containsKey(linkerdVersion)) {
                error("Unsupported Linkerd version: ${linkerdVersion}")
            }
            
            def versions = versionMapping[linkerdVersion]
            env.CRD_CHART_VERSION = versions.crd
            env.CONTROL_PLANE_CHART_VERSION = versions.controlPlane
            
            echo "📋 Version Mapping for Linkerd ${linkerdVersion}:"
            echo "  └── CRDs Chart Version: ${env.CRD_CHART_VERSION}"
            echo "  └── Control Plane Chart Version: ${env.CONTROL_PLANE_CHART_VERSION}"

            env.PATH = "$PATH:$PWD"
            sh """
                curl -sLO https://github.com/fluxcd/flux2/releases/download/v0.38.3/flux_0.38.3_linux_amd64.tar.gz
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
                flux reconcile kustomization -n flux-system ags-infrastructure --with-source || true
                flux reconcile kustomization -n flux-system linkerd --with-source || true

                sleep 120
            """

            sh "kubectl rollout restart deploy -n linkerd"
            sh "bash '$WORKSPACE/hosting/rollout/Q3-2025/linkerd-upgrade/reconcile-helmrelease.sh'"


            sh "kubectl rollout restart deploy -n linkerd"

            env.PATH = "$PATH:$PWD/.linkerd2/bin"
            def linkerdReleaseVer = getLinkerdReleaseVer(env.TARGET_LINKERD_VERSION)
            sh "curl -sL https://run.linkerd.io/install | LINKERD2_VERSION=${linkerdReleaseVer} sh"
            sh "curl -sL https://linkerd.github.io/linkerd-smi/install | sh"
            sh "linkerd check || true"

            successJobReply( "✅ Control Plane upgraded to 2.14" )

          }
        }
      }
    }

    stage('Enforce Authz') {
      when { expression { return shouldEnforceAuthz() } }
      steps {
        dir("$IAC_MANIFEST_DIR") {
          script {
            successJobReply( ":loading2: Will start enforcing Authz. Need user approval before proceeding :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )
         
            timeout(time: 60, unit: 'MINUTES') {
              input(
                id: 'userInput', 
                message: 'This will enforce authz/n for meshed workloads. Are you sure to proceed?', 
              )
            }
            def defaultPolicy = params.AUTHZ_POLICY

            sh """
                echo "🔍 Finding namespaces with Linkerd mesh or injection enabled..."

                inject_ns=\$(kubectl get ns -l linkerd.io/inject=enabled -o jsonpath='{.items[*].metadata.name}')
                proxy_ns=\$(kubectl get pods -A -o json | jq -r '
                  .items[] 
                  | select(.spec.containers[].name == "linkerd-proxy")
                  | .metadata.namespace
                ')
                all_ns=\$(echo "\${inject_ns}\\n\${proxy_ns}" | grep -vE "ext-"| sort -u)

                echo "📋 Namespaces with Linkerd mesh or injection: "
                echo "\$all_ns"

                echo "Annotating the namespaces for authz/n enforcement. Namespaces:"
                echo "\$all_ns"

                for ns in \$all_ns; do
                  echo "➡️  Annotating namespace: \$ns"
                  kubectl annotate namespace "\$ns" "config.linkerd.io/default-inbound-policy=${defaultPolicy}" --overwrite
                done
              """
            successJobReply( "✅ Authz Enforced. Default policy: ${defaultPolicy}" )

          }
        }
      }
    }

    stage('Upgrade Data Plane') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.VALIDATION_ONLY != true } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q3-2025/linkerd-upgrade") {
          script {

            successJobReply( ":loading2: Will start rolling restart meshed-workloads. Disruption might happen during this step. Need user approval before proceeding :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )

            timeout(time: 60, unit: 'MINUTES') {
              input(
                id: 'userInput', 
                message: 'This will rollout restart the deployment. Are you sure to proceed?', 
              )
            }

            sh "bash ./restart-per-pod.sh"
            sh "sleep 30"

            env.PATH = "$PATH:$PWD/.linkerd2/bin"
            def linkerdVersion = getLinkerdReleaseVer(env.TARGET_LINKERD_VERSION)
            sh "curl -sL https://run.linkerd.io/install | LINKERD2_VERSION=${linkerdVersion} sh"
            sh "curl -sL https://linkerd.github.io/linkerd-smi/install | sh"
            sh "linkerd check --proxy || true"

            successJobReply( "✅ Meshed-workloads restarted and Data Plane upgraded." )

          }
        }
      }
    }

    stage('Validation') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q3-2025/linkerd-upgrade") {
          script {

            env.TEST_NS = "linkerd-upgrade-check"
            env.DB_NS = "linkerd-db-check"
            env.TEST_IMAGE = "curlimages/curl"
            env.APP_IMAGE = "nginxdemos/hello"

            
            env.PATH = "$PATH:$PWD/.linkerd2/bin"
            def linkerdVersion = getLinkerdReleaseVer(env.TARGET_LINKERD_VERSION)
            sh "curl -sL https://run.linkerd.io/install | LINKERD2_VERSION=${linkerdVersion} sh"
            sh "curl -sL https://linkerd.github.io/linkerd-smi/install | sh"

            def cleanupScript = '''
                echo "Cleaning up test namespaces..."
                kubectl delete ns ${TEST_NS} --ignore-not-found=true
                kubectl delete ns ${DB_NS} --ignore-not-found=true
            '''
            try {
              sh "bash ./validate.sh"
              
              successJobReply( "✅ Validation is done. See validation result in :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )

            } finally {
                // Always run cleanup
                sh cleanupScript
            }
          }
        }
      }
    }
    
  }

  post {
    success {
      script {
        if (params.TARGET_ENVIRONMENT_NAME != 'blank') {
          summaryJobReply()
        }
      }
    }
    failure {
      script {
        if (params.TARGET_ENVIRONMENT_NAME != 'blank') {
          failedJobReply()
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

def formatDuration(def millis) {
  long ms = (long) millis

  def seconds = (ms.intdiv(1000)) % 60
  def minutes = (ms.intdiv(1000 * 60)) % 60
  def hours = (ms.intdiv(1000 * 60 * 60)) % 24
  def days = ms.intdiv(1000 * 60 * 60 * 24)

  return "${days > 0 ? days + 'd ' : ''}${hours > 0 ? hours + 'h ' : ''}${minutes}m ${seconds}s"
}

def successJobReply(String message) {
  withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
    def reply = new URL("https://slack.com/api/chat.postMessage").openConnection()
    def replyData = [
      channel  : "${env.slackChannel}",
      text     : ":green-ball: ${message}",
      thread_ts: "${env.slackThread}"
    ]

    def jsonReply = JsonOutput.toJson(replyData)
    reply.setRequestMethod("POST")
    reply.setDoOutput(true)
    reply.setRequestProperty("Content-Type", "application/json")
    reply.setRequestProperty("Authorization", "Bearer ${slackToken}")
    reply.getOutputStream().write(jsonReply.getBytes("UTF-8"))
    echo "Slack thread reply status: ${reply.getResponseCode()}"
  }
}

def failedJobReply() {
  withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
    def reply = new URL("https://slack.com/api/chat.postMessage").openConnection()
    def replyData = [
      channel  : "${env.slackChannel}",
      thread_ts: "${env.slackThread}",
      blocks   : [
        [
          type: "header",
          text: [ type: "plain_text", text: ":red-ball: Pipeline Failed!" ]
        ],
        [
          type: "section",
          text: [ type: "mrkdwn", text: "Something went wrong during the pipeline execution." ]
        ],
        [
          type: "divider"
        ],
        [
          type: "section",
          fields: [
            [ type: "mrkdwn", text: ":jenkins-cutes: <${env.BUILD_URL}console|*View Jenkins Console Output*>" ],
          ]
        ]
      ]
    ]

    def jsonReply = JsonOutput.toJson(replyData)
    reply.setRequestMethod("POST")
    reply.setDoOutput(true)
    reply.setRequestProperty("Content-Type", "application/json")
    reply.setRequestProperty("Authorization", "Bearer ${slackToken}")
    reply.getOutputStream().write(jsonReply.getBytes("UTF-8"))
    echo "Slack thread reply status: ${reply.getResponseCode()}"
  }
}

def summaryJobReply() {
  withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
    def trackerSheetUrl = "https://docs.google.com/spreadsheets/d/1G9HC1efLMQJ2Z9SrxNgFMdAgEZLk__JOhY-mp8z9nU8/edit?usp=sharing"
    def buildEndTime = System.currentTimeMillis()
    def buildStartTime = currentBuild.getStartTimeInMillis()
    def buildDuration = buildEndTime - buildStartTime

    // Format timestamps in Asia/Jakarta for display
    def startedAt = new Date(buildStartTime).format("HH:mm:ss", TimeZone.getTimeZone('Asia/Jakarta'))
    def endedAt = new Date(buildEndTime).format("HH:mm:ss", TimeZone.getTimeZone('Asia/Jakarta'))

    // Format timestamps in ISO 8601 UTC for Grafana
    def fromUtc = new Date(buildStartTime).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
    def toUtc = new Date(buildEndTime).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))

    // Compose Grafana URL with dynamic time range
    def athenaDatasourceUid = getAthenaDatasourceUid("${TARGET_ENVIRONMENT_NAME}")
    def grafanaUrl = "https://accelbyte.grafana.net/d/ddocd54os4um8e/service-downtime-metric?" +
        "orgId=1" +
        "&from=${URLEncoder.encode(fromUtc, 'UTF-8')}" +
        "&to=${URLEncoder.encode(toUtc, 'UTF-8')}" +
        "&timezone=browser" +
        "&var-EnvironmentName=${TARGET_ENVIRONMENT_NAME}" +
        "&var-ServiceName=justice-lobby-server" +
        "&var-StatusPageServiceName=Lobby" +
        "&var-athena_datasource=${athenaDatasourceUid}" +
        "&viewPanel=panel-7"

    def reply = new URL("https://slack.com/api/chat.postMessage").openConnection()
    def replyData = [
      channel  : "${env.slackChannel}",
      thread_ts: "${env.slackThread}",
      blocks   : [
        [
          type: "header",
          text: [ type: "plain_text", text: ":coin_spinning: Pipeline Completed Successfully!" ]
        ],
        [
          type: "section",
          text: [ type: "mrkdwn", text: "*Build Summary*\nPlease ensure the tracker is updated accordingly." ]
        ],
        [
          type: "divider"
        ],
        [
          type: "section",
          fields: [
            [ type: "mrkdwn", text: "*:bust_in_silhouette: Triggered by:*\n${env.userId}" ],
            [ type: "mrkdwn", text: "*:clock1: Start time:*\n${startedAt}" ],
            [ type: "mrkdwn", text: "*:clock4: End time:*\n${endedAt}" ],
            [ type: "mrkdwn", text: "*:hourglass_flowing_sand: Duration:*\n${formatDuration(buildDuration)}" ],
          ]
        ],
        [
          type: "divider"
        ],
        [
          type: "section",
          fields: [
            [ type: "mrkdwn", text: ":spreadsheet: <${trackerSheetUrl}|*Update Maintenance Tracker Sheet*>" ],
            [ type: "mrkdwn", text: ":grafana: <${grafanaUrl}|*View Grafana Downtime Panel*>" ],
            [ type: "mrkdwn", text: ":jenkins-cutes: <${env.BUILD_URL}|*View Pipeline Summary*>" ]
          ]
        ]
      ]
    ]

    def jsonReply = JsonOutput.toJson(replyData)
    reply.setRequestMethod("POST")
    reply.setDoOutput(true)
    reply.setRequestProperty("Content-Type", "application/json")
    reply.setRequestProperty("Authorization", "Bearer ${slackToken}")
    reply.getOutputStream().write(jsonReply.getBytes("UTF-8"))
    echo "Slack thread reply status: ${reply.getResponseCode()}"
  }
}

def sendSlackNotification() {
  withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
    def post = new URL("https://slack.com/api/chat.postMessage").openConnection()
    def postData = [
      channel: "${env.slackChannel}",
      blocks: [
        [
          type: "header",
          text: [ type: "plain_text", text: ":rocket2: ${env.JOB_BASE_NAME}" ]
        ],
        [
          type: "section",
          text: [ type: "mrkdwn", text: "*${env.TASK_TITLE}* \n${env.TASK_DESC}" ]
        ],
        [
          type: "section",
          fields: [
            [ type: "mrkdwn", text: "*Environment:* \n${params.TARGET_ENVIRONMENT_NAME}" ],
            [ type: "mrkdwn", text: "*Triggered by:* \n${env.userId}" ],
            [ type: "mrkdwn", text: "*Impact:* \n${env.TASK_IMPACT}" ],
            [ type: "mrkdwn", text: "*Pipeline URL:* \n<${env.BUILD_URL}|Click here>" ]
          ]
        ],
        [
          type: "context",
          elements: [[ type: "mrkdwn", text: "@hosting-platform-team @liveops-team please be informed about this activity." ]]
        ]
      ]
    ]

    def jsonPayload = groovy.json.JsonOutput.toJson(postData)
    post.setRequestMethod("POST")
    post.setDoOutput(true)
    post.setRequestProperty("Content-Type", "application/json")
    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"))

    def postRC = post.getResponseCode()
    println "Slack response: ${postRC}"

    if (postRC >= 200 && postRC < 300) {
      def response = post.getInputStream().getText()
      def parsed = new groovy.json.JsonSlurper().parseText(response)
      env.slackThread = parsed.ts
      echo "Slack thread_ts: ${env.slackThread}"
    } else {
      error "Failed to send Slack message. Status code: ${postRC}"
    }
  }
}

def getAthenaDatasourceUid(String environmentName) {
  withCredentials([string(credentialsId: "grafana_central_api_token", variable: 'grafanaToken')]) {
    def grafanaHost = 'https://accelbyte.grafana.net'
    def headers = [[name: 'Authorization', value: "Bearer " + grafanaToken]]

    def response = httpRequest(
      url: grafanaHost + "/api/datasources",
      httpMode: 'GET',
      customHeaders: headers,
      validResponseCodes: '200'
    )

    def datasources = new groovy.json.JsonSlurper().parseText(response.content)
    def matched = datasources.find { it.name == environmentName }

    if (!matched) {
      error "Datasource for environment '${environmentName}' not found."
    }

    return matched.uid
  }
}

void createPullRequests(String repoUrl, String branch, String commitMessage, String summary) {
  withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
    // POST
    def post = new URL(repoUrl).openConnection();
    def postData =  [
        title: commitMessage,
        source: [
            branch: [
                name: branch
            ]
        ],
        reviewers:[
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
          ]
        ],
        destination: [
            branch: [
                name: "master"
            ]
        ],
        summary: [
            raw: summary
        ],
        close_source_branch: true
    ]
    def jsonPayload = JsonOutput.toJson(postData)
    post.setRequestMethod("POST")
    post.setDoOutput(true)
    post.setRequestProperty("Content-Type", "application/json")
    post.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
    def postRC = post.getResponseCode();
    println "HTTP Response Code: ${postRC}"
    if (![200, 201].contains(postRC)) {
        def responseText
        responseText = post.errorStream?.text ?: "<no response body>"
        println "Response Body:\n${responseText}"
    }

    if(postRC.equals(200) || postRC.equals(201)) {
        def jsonSlurper = new JsonSlurper()
        def reply = post.getInputStream().getText()
        def replyMap = jsonSlurper.parseText(reply)
        prHtmlLink = replyMap.links.html.href
        def GREEN = "\u001B[32m"
        def RESET = "\u001B[0m"
        println "\n" + "="*80
        println "${GREEN}PR Link: ${prHtmlLink} ${RESET}"
        println "="*80 + "\n"
    }
  }
}

void pushChanges(String branch, String commitMessage, String pathspec){

  sshagent(['bitbucket-repo-read-only']) {

    sh """#!/bin/bash
        set -e
        export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

        git checkout -b "$branch"
        git config --global user.email "build@accelbyte.net"
        git config --global user.name "Build AccelByte"
        git status --short
        git add "$pathspec"
        git commit -m "$commitMessage" || true
        git push --set-upstream origin "$branch"
    """
  }
}

void getLinkerdReleaseVer(linkerdVersion) {
    def linkerdVersionMap = [
    '2.14'    : 'stable-2.14.10',
    '2.15'    : 'edge-24.2.4'
    ]

    if (!linkerdVersionMap.containsKey(linkerdVersion)) {
        throw new IllegalArgumentException("Unsupported Linkerd version: ${linkerdVersion}")
    }

    return linkerdVersionMap[linkerdVersion]
}

def shouldSendSlackNotif(isMainPipeline) {
    return params.TARGET_ENVIRONMENT_NAME != 'blank' &&
           isMainPipeline &&
           !params.VALIDATION_ONLY &&
           !params.SKIP_IAC_UPDATE &&
           !params.SKIP_PRE_CHECK
}

def shouldEnforceAuthz() {
    return params.TARGET_ENVIRONMENT_NAME != 'blank' && 
           !params.VALIDATION_ONLY &&
           params.TARGET_LINKERD_VERSION == "2.16"
}

def getLinkerdVersionMapping() {
    return [
        '2.14': [
            crd: '1.8.0',
            controlPlane: '1.16.11'
        ]
    ]
}