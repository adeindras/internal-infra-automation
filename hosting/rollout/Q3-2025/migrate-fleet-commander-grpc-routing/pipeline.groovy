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
String taskTitle = "Fleet Commander's gRPC Route Migration"
String taskDesc = "Migrate Fleet Commander gRPC routing that previously use ALB only, to go through Emissary."
String taskImpact = "Fleet Commander gRPC endpoint disruption"

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
      booleanParam(name: 'VALIDATION_ONLY', defaultValue: false, description: 'Run only the validation stage.'),
      booleanParam(name: 'CLEANUP_ONLY', defaultValue: false, description: 'Run only the cleanup stage.')

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
          env.userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]?.userId ?: 'Unknown'
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

          // env.ENVIRONMENT = sh (label: 'Set ENVIRONMENT', returnStdout: true, script: """#!/bin/bash
          // echo '$environmentDetails' | jq -r '.environment'
          // """).trim()

          def (customer, project, environment) = params.TARGET_ENVIRONMENT_NAME.split('-')

          env.ENVIRONMENT = environment


          env.AWS_ACCOUNT = sh (label: 'Set AWS_ACCOUNT', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.account'
          """).trim()

          env.AWS_REGION = sh (label: 'Set AWS_REGION', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.region'
          """).trim()

          env.DEPLOYMENT_DIR = "$WORKSPACE/deployments/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT}"
          
          env.OLD_INGRESS_PATH = "$DEPLOYMENT_DIR/services-overlay/justice-fleet-commander/infrastructure/ingress/ingress.yaml"
          env.NEW_INGRESS_PATH = "$DEPLOYMENT_DIR/services-overlay/emissary-ingress/justice-fleet-commander-ingress.yaml"
          env.NEW_INGRESS_KUST_PATH = "$DEPLOYMENT_DIR/services-overlay/emissary-ingress/kustomization.yaml"
          env.INGRESS_BACKUP_PATH = "$WORKSPACE/hosting/rollout/Q3-2025/migrate-fleet-commander-grpc-routing/ingress.bak.yaml"

          env.FC_MAPPING_PATH = "$DEPLOYMENT_DIR/services-overlay/justice-fleet-commander/infrastructure/ingress/path-mapping.yaml"
          env.FC_KUSTOMIZATION_PATH = "$DEPLOYMENT_DIR/services-overlay/justice-fleet-commander/kustomization.yaml"
          env.FC_GRPC_INGRESS_TEMPLATE_PATH = "$WORKSPACE/hosting/rollout/Q3-2025/migrate-fleet-commander-grpc-routing/templates/ingress-grpc-fleet-cmd.yaml"
          env.FC_MAPPING_GRPC_TEMPLATE_PATH = "$WORKSPACE/hosting/rollout/Q3-2025/migrate-fleet-commander-grpc-routing/templates/mapping.yaml"
          env.EMISSARY_HOST_OVERLAYS_PATH = "$DEPLOYMENT_DIR/services-overlay/emissary-ingress/emissary-resource.yaml"
          env.EMISSARY_HOST_SERVICES_PATH = "$DEPLOYMENT_DIR/services/emissary-ingress/emissary-resource.yaml"

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

    stage('Sending Slack Notification') {
      when { expression { return params.TARGET_ENVIRONMENT_NAME != 'blank' && isMainPipeline && params.VALIDATION_ONLY != true && params.CLEANUP_ONLY != true } }
      steps {
        script {
          sendSlackNotification()
        }
      }
    }

    stage('Migrate FC gRPC') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.VALIDATION_ONLY != true && params.CLEANUP_ONLY != true } }
      steps {
        dir('deployments') {
          checkout (
                changelog: false,
                poll: false,
                scm: scmGit(
                  branches: [[name: "master"]],
                  browser: bitbucket('https://bitbucket.org/accelbyte/deployments'),
                  extensions: [cloneOption(noTags: true, shallow: true)],
                  userRemoteConfigs: [[credentialsId: 'bitbucket-repo-read-only', url: 'git@bitbucket.org:accelbyte/deployments.git']]
                )
              )
        }
        dir("${DEPLOYMENT_DIR}") {
          script {
                        
            echo "🔄 Backing up original ingress and creating new ingress..."
            // Extract hostname from original ingress for later use
            def hostname = sh(
                script: "yq eval '.spec.rules[0].host' '${OLD_INGRESS_PATH}'",
                returnStdout: true
            ).trim()
            
            def ingress_name = sh(
                script: "yq eval '.metadata.name' '${OLD_INGRESS_PATH}'",
                returnStdout: true
            ).trim()

            if (!hostname || hostname == "null") {
                error("Could not extract hostname from original ingress")
            }

            if (!ingress_name || ingress_name == "null") {
                error("Could not extract metadata.name from original ingress")
            }
            
            env.ORIGINAL_HOSTNAME = hostname
            env.ORIGINAL_INGRESS_NAME = ingress_name

            echo "📝 Found original hostname: ${env.ORIGINAL_HOSTNAME}"
            echo "📝 Found original ingress name: ${env.ORIGINAL_INGRESS_NAME}"

            
            // Copy original ingress to new location
            sh "cp '${OLD_INGRESS_PATH}' '${NEW_INGRESS_PATH}'"

            sh """
                yq eval -i '.metadata.namespace = "emissary"' '${NEW_INGRESS_PATH}'
                yq eval -i '.spec.rules[0].http.paths[0].backend.service.name = "emissary-ingress"' '${NEW_INGRESS_PATH}'
                yq eval -i '.spec.rules[0].http.paths[0].backend.service.port.number = 443' '${NEW_INGRESS_PATH}'
            """

            echo "🔄 Updating kustomization.yaml..."
            
            // Check if justice-fleet-commander-ingress.yaml is already in resources
            def existsInKustomization = sh(
                script: "yq eval '.resources[] | select(. == \"justice-fleet-commander-ingress.yaml\")' '${NEW_INGRESS_KUST_PATH}'",
                returnStdout: true
            ).trim()
            
            if (!existsInKustomization) {
                // Add justice-fleet-commander-ingress.yaml to resources array
                sh """
                    yq eval -i '.resources += ["./justice-fleet-commander-ingress.yaml"]' '${NEW_INGRESS_KUST_PATH}'
                """
                echo "✅ Added justice-fleet-commander-ingress.yaml to kustomization.yaml"
            } else {
                echo "⚠️ justice-fleet-commander-ingress.yaml already exists in kustomization.yaml"
            }

            echo "🔄 Updating Emissary Host manifest..."            
            // Find the document index for Host with name emissary-host
            def docIndex = sh(
                script: """
                    yq eval-all 'select(.kind == "Host" and .metadata.name == "emissary-host") | document_index' '${EMISSARY_HOST_OVERLAYS_PATH}'
                """,
                returnStdout: true
            ).trim()
            
            if (!docIndex || docIndex == "null") {
                error("Could not find Host manifest with name emissary-host")
            }
            
            echo "📝 Found emissary-host manifest at document index: ${docIndex}"
            
            // Check if alpn_protocols already exists
            def alpnExists = sh(
                script: """
                    yq eval-all 'select(document_index == ${docIndex}) | .spec.tls.alpn_protocols' '${EMISSARY_HOST_OVERLAYS_PATH}'
                """,
                returnStdout: true
            ).trim()
            
            if (alpnExists == "null" || !alpnExists) {
                // Add alpn_protocols to the Host manifest
                sh """
                    yq eval-all -i '(select(document_index == ${docIndex}) | .spec.tls.alpn_protocols) = "h2,http/1.1"' '${EMISSARY_HOST_OVERLAYS_PATH}'
                """
                echo "✅ Added ALPN protocols to emissary-host manifest"
            } else {
                echo "⚠️ ALPN protocols already configured: ${alpnExists}"
            }

            echo "🔄 Generating new GRPC mapping..."
            sh """
            echo "---" >> '${FC_MAPPING_PATH}'
            cat '${FC_MAPPING_GRPC_TEMPLATE_PATH}' >> '${FC_MAPPING_PATH}'
            """

            def branchName = "automation-migrate-fleet-commander-grpc-routing-${TARGET_ENVIRONMENT_NAME}"
            def commitMsg  = "feat: migrate fleet commander grpc routing to use emissary ${TARGET_ENVIRONMENT_NAME}"
            def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests"
            def prSummary= "Migrate Fleet Commander GRPC routing to use Emissary ${TARGET_ENVIRONMENT_NAME}"
            
            pushChanges(branchName, commitMsg, DEPLOYMENT_DIR)
            createPullRequests(repoUrl, branchName, commitMsg, prSummary)


            // Update with your message here
            successJobReply( "PR for migrating Fleet Commander gRPC is created. Please review and merge the PR. Confirm the merge in :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )
            
            timeout(time: 45, unit: 'MINUTES') {
              input(
                id: 'userInput', 
                message: 'Proceed if the PR is merged', 
              )
            }
          }
        }
        
        
      }
    }

    stage('Cleanup Old Ingress') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.VALIDATION_ONLY != true } }
      steps {
        dir('deployments') {
          checkout (
                changelog: false,
                poll: false,
                scm: scmGit(
                  branches: [[name: "master"]],
                  browser: bitbucket('https://bitbucket.org/accelbyte/deployments'),
                  extensions: [cloneOption(noTags: true, shallow: true)],
                  userRemoteConfigs: [[credentialsId: 'bitbucket-repo-read-only', url: 'git@bitbucket.org:accelbyte/deployments.git']]
                )
              )
        }
        dir("${DEPLOYMENT_DIR}") {
          script {
                        
            echo "🗑️ Removing original ingress..."
            
            sh "cp '${OLD_INGRESS_PATH}' '${INGRESS_BACKUP_PATH}'"
            sh "rm '${OLD_INGRESS_PATH}'"
            sh "yq eval 'del(.resources[] | select(. == \"./infrastructure/ingress/ingress.yaml\"))' -i '${FC_KUSTOMIZATION_PATH}'"

            def branchName = "automation-cleanup-fleet-commander-old-ingress-${TARGET_ENVIRONMENT_NAME}"
            def commitMsg  = "chores: remove fleet commander old grpc ingress after migration to emissary ${TARGET_ENVIRONMENT_NAME}"
            def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests"
            def prSummary= "Remove fleet commander old grpc ingress after migration to emissary${TARGET_ENVIRONMENT_NAME}"
            
            pushChanges(branchName, commitMsg, DEPLOYMENT_DIR)
            createPullRequests(repoUrl, branchName, commitMsg, prSummary)
            
            successJobReply( "Please check the ingress removal PR in :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )
            timeout(time: 45, unit: 'MINUTES') {
              input(
                id: 'userInput', 
                message: 'Please review and merge the PR before proceeding!!!', 
              )
            }

            sh "cat '${INGRESS_BACKUP_PATH}'"
            sh "kubectl delete -f '${INGRESS_BACKUP_PATH}' --dry-run=server"
            successJobReply( "Please check dry-run output in :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )
            timeout(time: 45, unit: 'MINUTES') {
              input(
                id: 'userInput', 
                message: 'Check the Dry-run above before deleting the ingress. Proceed with ingress deletion?', 
              )
            }
            sh "kubectl delete -f '${INGRESS_BACKUP_PATH}'"

            // Update with your message here
            successJobReply( "✅ Removing Old Ingress is completed." )

          }
        }
        
        
      }
    }


    stage('Validation - gRPC Connectivity Test') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && params.CLEANUP_ONLY != true } }
      steps {
        dir('justice-fleet-commander') {
          checkout (
                changelog: false,
                poll: false,
                scm: scmGit(
                  branches: [[name: "main"]],
                  browser: bitbucket('https://bitbucket.org/accelbyte/justice-fleet-commander'),
                  extensions: [cloneOption(noTags: true, shallow: true)],
                  userRemoteConfigs: [[credentialsId: 'bitbucket-repo-read-only', url: 'git@bitbucket.org:accelbyte/justice-fleet-commander.git']]
                )
              )
        }

        dir("$WORKSPACE/hosting/rollout/Q3-2025/migrate-fleet-commander-grpc-routing") {
          // Add your scripts here
          script {

            echo "🧪 Running validation checks..."

                        sh label: 'Installing grpcurl', script: '''
                set +x
                echo "Installing grpcurl..."
  
                # Method 2: Download pre-built binary
                GRPCURL_VERSION="1.9.3"
                ARCH="linux_x86_64"
                
                curl -L -o grpcurl.tar.gz "https://github.com/fullstorydev/grpcurl/releases/download/v${GRPCURL_VERSION}/grpcurl_${GRPCURL_VERSION}_${ARCH}.tar.gz"
                tar -xzf grpcurl.tar.gz
                chmod +x grpcurl
                rm grpcurl.tar.gz
                
            '''
            env.PATH = "$PATH:$WORKSPACE/hosting/rollout/Q3-2025/migrate-fleet-commander-grpc-routing"
                        
            // 1. Validate deployed Host resource has alpn_protocols
            echo "🔍 Validating deployed Host resource configuration..."
            def alpnProtocols = sh(
                script: """
                    set +x
                    kubectl get host emissary-host -n emissary -o json | jq -r '.spec.tls.alpn_protocols // "null"'
                """,
                returnStdout: true
            ).trim()
            
            if (alpnProtocols == "null" || !alpnProtocols) {
                error("❌ Deployed Host resource validation failed: alpn_protocols not found")
            } else if (alpnProtocols.contains("h2") && alpnProtocols.contains("http/1.1")) {
                echo "✅ Deployed Host resource validation passed: alpn_protocols = ${alpnProtocols}"
            } else {
                echo "⚠️ Deployed Host resource validation warning: unexpected alpn_protocols = ${alpnProtocols}"
            }

            echo "🔍 Validating new Kubernetes Ingress deployment..."
            def ingressName = sh(
                script: """
                    set +x
                    kubectl get ingress -n emissary --no-headers -o custom-columns=":metadata.name" | grep fleet-commander | head -1
                """,
                returnStdout: true
            ).trim()
            
            if (ingressName) {
                echo "✅ New Kubernetes Ingress found: ${ingressName}"
                
                // Get ingress details
                def ingressDetails = sh(
                    script: """
                        set +x
                        kubectl get ingress ${ingressName} -n emissary -o json | jq -r '"Host: " + .spec.rules[0].host + ", Backend: " + .spec.rules[0].http.paths[0].backend.service.name + ":" + (.spec.rules[0].http.paths[0].backend.service.port.number | tostring) + ", Class: " + (.spec.ingressClassName // "default")'
                    """,
                    returnStdout: true
                ).trim()
                echo "📋 Ingress details: ${ingressDetails}"
                
                // Check ingress status/address
                def ingressStatus = sh(
                    script: """
                        set +x
                        kubectl get ingress ${ingressName} -n emissary -o json | jq -r '.status.loadBalancer.ingress[0].hostname // .status.loadBalancer.ingress[0].ip // "pending"'
                    """,
                    returnStdout: true
                ).trim()
                echo "📋 Ingress status: ${ingressStatus}"
            } else {
                echo "⚠️ New Kubernetes Ingress not found (may not be deployed yet)"
            }

            echo "🧪 Running gRPC connectivity validation..."

            def ingress_host = sh(
                script: """
                    set +x
                    kubectl get ingress ${ingressName} -n emissary -o json | jq -r '.spec.rules[0].host'
                """,
                returnStdout: true
              ).trim()


            if (ingress_host == null || ingress_host.trim() == 'null') {
                //Fallback to emissary-ingress
                ingress_host = sh(
                script: """
                    set +x
                    kubectl get ingress emissary-ingress -n emissary -o json | jq -r '.spec.rules[0].host'
                """,
                returnStdout: true
              ).trim()
            }

            env.GRPC_ENDPOINT = "${ingress_host}:443"
            env.GRPC_SERVICE_PATH = "fleet_commander.v1.ArtifactConfigurationsService/GetArtifactConfiguration"
            

            dir("$WORKSPACE/justice-fleet-commander/proto"){
              def grpcResult = sh(
                  label: 'Call gRPC endpoint',
                  script: """
                      set +x
                      echo "Calling gRPC endpoint..."
                      grpcurl -vv -H "Authorization: asdas" -proto api/artifacts/v1/artifacts.proto \\
                          ${GRPC_ENDPOINT} \\
                          fleet_commander.artifacts.v1.ArtifactConfigurationsEndpoint/GetArtifactConfiguration 2>&1 || true
                  """,
                  returnStdout: true
              ).trim()

              if (grpcResult.contains("Response trailers received:") || 
                  grpcResult.contains("Sent 0 requests and received 0 responses")) {
                  
                  if (grpcResult.contains("Code: Unauthenticated") || 
                      grpcResult.contains("invalid token")) {
                      echo "✅ gRPC service is reachable! Authentication error detected (expected without valid JWT token)"
                      echo "📋 Connection test passed - service is responding to requests"
                  } else {
                      echo "⚠️  gRPC service reachable but returned error:"
                      echo grpcResult
                  }
              } else {
                  echo "🔍 Unexpected response format:"
                  echo grpcResult
                  error "❌ Cannot connect to gRPC service: ${grpcResult}"
              }
            }

            // Update with your message here
            successJobReply( "✅ Validation completed" )
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
          elements: [[ type: "mrkdwn", text: "@/hosting-platform-team @/liveops-team please be informed about this activity." ]]
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
  echo '$eksClusters' | jq '.resources[] | select(.name == "$envName") | {name: .name, region: .region, account: .account} + {customerName: .details.Tags[] | select (.Key == "customer_name").Value} + {project: .details.Tags[] | select (.Key == "project").Value} + {environment: .details.Tags[] | select (.Key == "environment").Value}'
  """).trim()

  return environmentDetail
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