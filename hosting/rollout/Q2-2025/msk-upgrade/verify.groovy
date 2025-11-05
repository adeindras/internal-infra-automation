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
String taskTitle = "Check MSK Topics for Upgrade"
String taskDesc = "Check MSK topics before an upgrade, to ensure minimum downtime"
String taskImpact = "No impact"

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
      string(name: 'KAFKA_BROKERS_SSM_PATH', defaultValue: '', description: 'Path to Kafka broker SSM parameter'),
      string(name: 'KAFKA_USERNAME_SSM_PATH', defaultValue: '', description: 'Path to Kafka username SSM parameter'),
      string(name: 'KAFKA_PASSWORD_SSM_PATH', defaultValue: '', description: 'Path to Kafka password SSM parameter')
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
          env.userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
          currentBuild.displayName = "#${BUILD_NUMBER} - ${TARGET_ENVIRONMENT_NAME} - ${env.userId}"

          def environmentDetails = getEnvironmentDetails(params.TARGET_ENVIRONMENT_NAME)
          env.TARGET_ENVIRONMENT_NAME = params.TARGET_ENVIRONMENT_NAME

          env.TASK_TITLE = taskTitle
          env.TASK_DESC = taskDesc
          env.TASK_IMPACT = taskImpact
          env.CHANNEL_REPORT_INFRA = channelReportInfra
          env.CHANNEL_FOR_TESTING = channelForTesting

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

          // Pipelines located in the DEVELOPMENT folder (or any non-standard naming) will send notifications to #my-test-channel.
          // Pipelines under the STABLE folder will default to sending notifications #report-infra-changes.
          if (WORKSPACE.contains("DEVELOPMENT")) {
              env.slackChannel = env.CHANNEL_FOR_TESTING
          } else if (WORKSPACE.contains("STABLE")) {
              env.slackChannel = env.CHANNEL_REPORT_INFRA
          } else {
              env.slackChannel = env.CHANNEL_FOR_TESTING
          }
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


        script {
          def bitbucketModule = load("$WORKSPACE/hosting/rollout/Q2-2025/msk-upgrade/bitbucket.groovy")
        }
      }
    }

    stage('Sending Slack Notification') {
      when { expression { return params.TARGET_ENVIRONMENT_NAME != 'blank' && isMainPipeline } }
      steps {
        script {
          sendSlackNotification()
        }
      }
    }

    stage('Run-1') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/msk-upgrade") {
          script {
            successJobReply( "Verification is completed" )
            env.kafkaUsername = sh (label: 'get Kafka username',
                returnStdout: true,
                script: "aws ssm get-parameter --with-decryption --name ${params.KAFKA_USERNAME_SSM_PATH} --query 'Parameter.Value' | tr -d '\\\"'").trim()
            env.kafkaPassword = sh (label: 'get Kafka password',
                returnStdout: true,
                script: "aws ssm get-parameter --with-decryption --name ${params.KAFKA_PASSWORD_SSM_PATH} --query 'Parameter.Value' | tr -d '\\\"'").trim()
            env.kafkaBrokerAddress = sh (label: 'get Kafka broker addresses',
                returnStdout: true,
                script: "aws ssm get-parameter --with-decryption --name ${params.KAFKA_BROKERS_SSM_PATH} --query 'Parameter.Value' | tr -d '\\\"'").trim()
            sh "sed -i 's#USERNAME#$kafkaUsername#' jumpbox/manifests/ssl-user-config.properties.template"
            sh "sed -i 's#PASSWORD#$kafkaPassword#' jumpbox/manifests/ssl-user-config.properties.template"
            sh "mv jumpbox/manifests/ssl-user-config.properties.template jumpbox/manifests/ssl-user-config.properties"
            sh "bash verify_topics.sh $kafkaBrokerAddress"
            archiveArtifacts artifacts: 'result-*.csv', fingerprint: true
          }
        }
      }
    }
  }

  post {
    success {
      script {
        if ( params.TARGET_ENVIRONMENT_NAME != 'blank' ) {
          summaryJobReply()
        }
      }
    }
    failure {
      script {
        if ( params.TARGET_ENVIRONMENT_NAME != 'blank' ) {
          failedJobReply()
        }
      }
    }
  }
}

// ────────────────────────────────────────────────
// ───── Helper Functions ─────────────────────────
// ────────────────────────────────────────────────

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
