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
String taskTitle = "Aurora Provisioned <> Serverless Switch"
String taskDesc = "RDS Aurora will be migrated to Provisioned/Serverless."
String taskImpact = "Short disruption for service utilizing the target RDS Aurora during failover. Disruptions last < 1 minutes. Failover will happen a few times."

// Slack channel ID for sending test notifications.
// Defaults to #my-test-channel.
// Update this value if you'd prefer to use your own testing channel.
String channelForTesting = "C091YBUH465"

// This will sent to #report-infra-changes.
String channelReportInfra = "C017L2M1C3D"

def envList = getEnvironmentList()
properties(
  [
    parameters([
      choice(choices: envList, description: "Target environment name", name: "TARGET_ENVIRONMENT_NAME"),
      string(description: "Target RDS Aurora IaC directory. Relative to RDS Aurora terragrunt dir. e.g justice-shared,justice-shared-pg16", name: 'TARGET_AURORA_IAC_DIR', defaultValue: "justice-shared"),
      choice(choices: ["provisioned","serverless"], description: "Target Engine mode", name: "TARGET_ENGINE_MODE"),
      text(description: "Optional. Only set this for external AWS account environment e.g Dreamhaven Stage, Dreamhaven Prod etc", name: 'EXT_ACC_AWS_CREDS', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),
      booleanParam(defaultValue: false, description: "Run only IaC drift reconciliation (skip switching stages)", name: 'RUN_RECONCILE_ONLY'),
      booleanParam(defaultValue: false, description: "Run only cleanup of temporary instance(s)", name: 'RUN_CLEANUP_ONLY')
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

          //Workaround for staging vs stage issue
          def (customer, project, environment) = params.TARGET_ENVIRONMENT_NAME.split('-')
          env.ENVIRONMENT = environment

          env.AWS_ACCOUNT = sh (label: 'Set AWS_ACCOUNT', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.account'
          """).trim()

          env.AWS_REGION = sh (label: 'Set AWS_REGION', returnStdout: true, script: """#!/bin/bash
          echo '$environmentDetails' | jq -r '.region'
          """).trim()

          env.TARGET_ENV_IAC_DIR = "$WORKSPACE/iac/live/$AWS_ACCOUNT/$CUSTOMER_NAME/$PROJECT/$AWS_REGION/$ENVIRONMENT"
          env.TARGET_AURORA_IAC_DIR = params.TARGET_AURORA_IAC_DIR

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
          env.CURRENT_ENGINE_MODE = params.TARGET_ENGINE_MODE == 'serverless' ? 'provisioned' : 'serverless'
        
          if (!fileExists("$TARGET_ENV_IAC_DIR/rds_aurora/$CURRENT_ENGINE_MODE/$TARGET_AURORA_IAC_DIR/terragrunt.hcl")) {
              echo "Engine already switched to ${params.TARGET_ENGINE_MODE}"
              env.CURRENT_ENGINE_MODE = params.TARGET_ENGINE_MODE
          }
        }

        dir("$TARGET_ENV_IAC_DIR/rds_aurora/$CURRENT_ENGINE_MODE/$TARGET_AURORA_IAC_DIR"){


            script {

                //CLuster name
                def outputJson = runTerragruntOutput()

                // Parse JSON into Groovy object (map)
                def tfOutputs = readJSON text: outputJson

                // Helper closure to read a value
                def tfOutput = { key ->
                  return tfOutputs[key]?.value
                }
                env.AURORA_CLUSTER_ARN = tfOutput("cluster_arn")
                env.AURORA_CLUSTER_ID = env.AURORA_CLUSTER_ARN.replaceAll('"', '').split(":")[-1]
                
                def rawJson = sh(
                    script: """
                      aws rds describe-db-instances \
                        --query "DBInstances[?DBClusterIdentifier=='${AURORA_CLUSTER_ID}']" \
                        --output json
                    """,
                    returnStdout: true
                ).trim()

                def parsed = readJSON text: rawJson
                def matching = []

                for (inst in parsed) {
                    def instanceId = inst.DBInstanceIdentifier
                    def dbClass = inst.DBInstanceClass
                    //Infer instance engine mode using instance type
                    def mode = dbClass?.contains("serverless") ? "serverless" : "provisioned"
                    if (mode == params.TARGET_ENGINE_MODE) {
                        matching << instanceId
                    }
                }

            }
        }

        // Configure kubeconfig
        sh "aws eks --region ${AWS_REGION} --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform update-kubeconfig --name ${TARGET_ENVIRONMENT_NAME}"
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

    stage('Provision New Aurora Reader') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && !params.RUN_RECONCILE_ONLY && !params.RUN_CLEANUP_ONLY } }
      steps {
        dir("$WORKSPACE/hosting/pipeline/aurora-provisioned-x-serverless-switch") {
          script {
 
            def baseName = env.AURORA_CLUSTER_ID[0..-9]
            def readerInstanceID = "${baseName}-temp"

            def engineMode = params.TARGET_ENGINE_MODE

            def instanceClass = ""
            def instancesJson = sh(
                script: """aws rds describe-db-clusters \
                    --db-cluster-identifier ${AURORA_CLUSTER_ID} \
                    --query 'DBClusters[0].DBClusterMembers[*].DBInstanceIdentifier' \
                    --output json""",
                returnStdout: true
            ).trim()

            def instanceIds = readJSON text: instancesJson

            if( engineMode == "provisioned") {
              def scalingJson = sh(
                  script: """aws rds describe-db-clusters \
                      --db-cluster-identifier ${AURORA_CLUSTER_ID} \
                      --query 'DBClusters[0].ServerlessV2ScalingConfiguration' \
                      --output json""",
                  returnStdout: true
              ).trim()

              def scalingInfo = readJSON text: scalingJson

              def minAcu = scalingInfo.MinCapacity.toString()
              def maxAcu = scalingInfo.MaxCapacity.toString()

              instanceClass = convertAuroraServerlessToProvisioned(minAcu, maxAcu)
              echo "Recommended instance class: ${instanceClass}"
            } else {

              def instanceId = instanceIds[0]

              def instanceClassJson = sh(
                  script: """aws rds describe-db-instances \
                      --db-instance-identifier ${instanceId} \
                      --query 'DBInstances[0].DBInstanceClass' \
                      --output text""",
                  returnStdout: true
              ).trim()

              instanceClass = instanceClassJson.toString()

              def acu = convertProvisionedToAuroraServerlessACU(instanceClass)
              echo "Recommended ACU - Min: ${acu.min} Max: ${acu.max}"

              sh """
                aws rds modify-db-cluster --db-cluster-identifier ${AURORA_CLUSTER_ID} \
                    --serverless-v2-scaling-configuration MinCapacity=${acu.min},MaxCapacity=${acu.max}
              """
            }

            sh "bash ./downgrade-fo-tier.sh ${AURORA_CLUSTER_ID}"
            instanceIds.each { id ->
              sh "bash ./check-readiness.sh ${id}"
            }

            successJobReply( "All Aurora instance's Failover tier in cluster ${AURORA_CLUSTER_ID} is downgraded to 2" )

            successJobReply( ":loading2: Provisioning temporary ${params.TARGET_ENGINE_MODE} Aurora reader" )
            sh "bash ./add-reader.sh -c ${AURORA_CLUSTER_ID} -r ${readerInstanceID} -m ${engineMode} -i ${instanceClass}"
            sh "bash ./check-readiness.sh ${readerInstanceID}"
          }

        }
        script {
          // Update with your message here
          successJobReply( "New ${params.TARGET_ENGINE_MODE} Aurora reader is available" )
        }
      }
    }

    // //To-Do: instead of recreeating the writer. Now Aurora supports changing instance type from provisioned to serverless
    stage('Modify Old Writer instance') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && !params.RUN_RECONCILE_ONLY && !params.RUN_CLEANUP_ONLY } }
      steps {
        dir("$WORKSPACE/hosting/pipeline/aurora-provisioned-x-serverless-switch") {
          script {

            def baseName = env.AURORA_CLUSTER_ID[0..-9]
            def readerInstanceID = "${baseName}-temp"

            successJobReply( ":warning: Disruption might happen during promotion. Promotion starts after user approval :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )
            timeout(time: 60, unit: 'MINUTES') {
                input(
                  id: 'userInput', 
                  message: 'This will promote the new Aurora Reader. This might lead to disruption. Do you want to proceed?', 
                )
              }

            retry(3) {
              sh "bash ./promote.sh ${AURORA_CLUSTER_ID} ${readerInstanceID}"
              sleep 15
              sh "bash ./check-promotion.sh ${AURORA_CLUSTER_ID} ${readerInstanceID}"
            }
            
            successJobReply( "${readerInstanceID} is now the writer" )


            def oldWriterId = baseName

            //Fetch instance class early before old writer deletion
            def instanceClassJson = sh(
                  script: """aws rds describe-db-instances \
                      --db-instance-identifier ${oldWriterId} \
                      --query 'DBInstances[0].DBInstanceClass' \
                      --output text""",
                  returnStdout: true
              ).trim()
            def instanceClass = instanceClassJson.toString()

            def engineMode = params.TARGET_ENGINE_MODE

            if( engineMode == "provisioned") {
              def scalingJson = sh(
                  script: """aws rds describe-db-clusters \
                      --db-cluster-identifier ${AURORA_CLUSTER_ID} \
                      --query 'DBClusters[0].ServerlessV2ScalingConfiguration' \
                      --output json""",
                  returnStdout: true
              ).trim()

              def scalingInfo = readJSON text: scalingJson

              def minAcu = scalingInfo.MinCapacity.toString()
              def maxAcu = scalingInfo.MaxCapacity.toString()

              instanceClass = convertAuroraServerlessToProvisioned(minAcu, maxAcu)
              echo "Recommended instance class: ${instanceClass}"
            } 

            successJobReply( ":loading2: Modifying old writer: ${oldWriterId} to ${params.TARGET_ENGINE_MODE}..." )
            sh "bash ./modify-instance-type.sh ${oldWriterId} ${engineMode} ${instanceClass}"
            //Workaround for the update delay
            sleep 30
            sh "bash ./check-readiness.sh ${oldWriterId}"
            successJobReply( "Old writer ${oldWriterId} with ${params.TARGET_ENGINE_MODE} engine successfully provisioned." )

            successJobReply( ":warning: Disruption might happen during promotion. Promoting old writer without user approval :loading2:" )
            retry(3) {
              sh "bash ./promote.sh ${AURORA_CLUSTER_ID} ${oldWriterId}"
              sleep 15
              sh "bash ./check-promotion.sh ${AURORA_CLUSTER_ID} ${oldWriterId}"
            }
            
            successJobReply( "${oldWriterId} is now the writer" )
          }
          

        }
        script {
          // Update with your message here
          successJobReply( "Old writer re-creation completed!" )
        }
      }
    }

    stage('Reconciling IaC Drift') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && !params.RUN_CLEANUP_ONLY } }
      steps {
        dir("$TARGET_ENV_IAC_DIR/rds_aurora") {
          script {

            def targetEngineMode = params.TARGET_ENGINE_MODE
            def engineVersion = sh(
                script: """aws rds describe-db-clusters \
                    --db-cluster-identifier ${AURORA_CLUSTER_ID} \
                    --query 'DBClusters[0].EngineVersion' \
                    --output json""",
                returnStdout: true
            ).trim().replaceAll('"', '')

            sh "mkdir -p ${targetEngineMode}"
            sh "cp -r ${CURRENT_ENGINE_MODE}/${TARGET_AURORA_IAC_DIR} ${targetEngineMode}/"
            sh "ls ${targetEngineMode}/"
            
            dir("$TARGET_ENV_IAC_DIR/rds_aurora/$CURRENT_ENGINE_MODE/$TARGET_AURORA_IAC_DIR"){
              runTerragruntStatePull()
            }

            sh "cp '$CURRENT_ENGINE_MODE/$TARGET_AURORA_IAC_DIR/terraform.tfstate' '$targetEngineMode/$TARGET_AURORA_IAC_DIR' "
            sh "ls ${targetEngineMode}/$TARGET_AURORA_IAC_DIR"

            sh """
              if jq empty $targetEngineMode/$TARGET_AURORA_IAC_DIR/terraform.tfstate >/dev/null 2>&1; then
                echo "✅ TF State JSON is valid"
              else
                echo "❌ TF State is invalid JSON"
                exit 1
              fi
            """

            sh "rm -rf $CURRENT_ENGINE_MODE/${TARGET_AURORA_IAC_DIR}"

            dir("$TARGET_ENV_IAC_DIR/rds_aurora/$targetEngineMode/$TARGET_AURORA_IAC_DIR"){
              sh "ls -lah"
              
              def stateFile = '/tmp/terraform-state.json'
              def newStateFile = '/tmp/terraform-state.tmp.json'
              sh """
                tfenv install
                yes no | AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt state pull > ${stateFile} 2>/dev/null
              """
              def rawSerial = sh(script: "jq -r '.serial' ${stateFile}", returnStdout: true).trim()
              def remoteSerial = rawSerial.toInteger()
              def newSerial = remoteSerial + 1

              sh """
                jq ".serial = ${newSerial}" "terraform.tfstate" > "$newStateFile"
                mv "$newStateFile" "./terraform.tfstate"
              """

              echo "Pushing latest state..."
              runTerragruntWithYes("state push -force ./terraform.tfstate")

              //To-Do: Consider case where the cluster use auto minor upgrade and there's a drift between tf manifest and actual state
              if (targetEngineMode == "provisioned") {
                  // Remove equal sign from 'inputs' and 'serverlessv2_scaling_configuration' to make it readable by hcledit
                  sh "sed -i '/[[:space:]]*inputs[[:space:]]*=/c\\ inputs {' terragrunt.hcl"
                  sh "sed -i '/[[:space:]]*serverlessv2_scaling_configuration[[:space:]]*=/c\\ serverlessv2_scaling_configuration {' terragrunt.hcl"
                  
                  def minAcu = sh (returnStdout: true, script: """#!/bin/bash
                  cat 'terragrunt.hcl' | hcledit attribute get inputs.serverlessv2_scaling_configuration.min_capacity
                  """).trim().replaceAll('"', '')
                  def maxAcu = sh (returnStdout: true, script: """#!/bin/bash
                  cat 'terragrunt.hcl' | hcledit attribute get inputs.serverlessv2_scaling_configuration.max_capacity
                  """).trim().replaceAll('"', '')
                  echo "$minAcu"
                  echo "$maxAcu"
                  def instanceClass = convertAuroraServerlessToProvisioned(minAcu, maxAcu)
                  

                  sh "hcledit -u -f terragrunt.hcl attribute set inputs.instance_class '\"$instanceClass\"'"
                  echo "${engineVersion}"
                  sh "hcledit -u -f terragrunt.hcl attribute set inputs.engine_version '\"$engineVersion\"'"

                  
                  sh "sed -i '/[[:space:]]*serverlessv2_scaling_configuration[[:space:]]*{/c\\ serverlessv2_scaling_configuration = {' terragrunt.hcl"
                  sh "hcledit -u -f terragrunt.hcl attribute rm inputs.serverlessv2_scaling_configuration"

                  // Revert the equal sign after inputs
                  sh "sed -i '/[[:space:]]*inputs[[:space:]]*{/c\\ inputs = {' terragrunt.hcl"

              } else {


                // Remove equal sign from 'inputs' to make it readable by hcledit
                sh "sed -i '/[[:space:]]*inputs[[:space:]]*=/c\\ inputs {' terragrunt.hcl"
            
                //Infer instanceClass from TG manifest
                def instanceClass = sh (returnStdout: true, script: """#!/bin/bash
                cat 'terragrunt.hcl' | hcledit attribute get inputs.instance_class
                """).trim().replaceAll('"', '')

                def acu = convertProvisionedToAuroraServerlessACU(instanceClass)
                echo "Recommended ACU - Min: ${acu.min} Max: ${acu.max}"

                sh "hcledit -u -f terragrunt.hcl attribute set inputs.instance_class '\"db.serverless\"'"
                echo "${engineVersion}"
                sh "hcledit -u -f terragrunt.hcl attribute set inputs.engine_version '\"$engineVersion\"'"

                sh "hcledit -u -f terragrunt.hcl  attribute append inputs.serverlessv2_scaling_configuration '{}' --newline"

                // Remove equal sign from 'serverlessv2_scaling_configuration' to make it readable by hcledit
                sh "sed -i '/[[:space:]]*serverlessv2_scaling_configuration[[:space:]]*=/c\\ serverlessv2_scaling_configuration {}' terragrunt.hcl"

                sh "hcledit -u -f terragrunt.hcl  attribute append inputs.serverlessv2_scaling_configuration.auto_pause 'false' --newline"
                sh "hcledit -u -f terragrunt.hcl  attribute append inputs.serverlessv2_scaling_configuration.min_capacity '${acu.min}'"
                sh "hcledit -u -f terragrunt.hcl  attribute append inputs.serverlessv2_scaling_configuration.max_capacity '${acu.max}'"



                // Revert the equal sign after inputs and serverlessv2_scaling_configuration
                sh "sed -i '/[[:space:]]*inputs[[:space:]]*{/c\\ inputs = {' terragrunt.hcl"
                sh "sed -i '/[[:space:]]*serverlessv2_scaling_configuration[[:space:]]*{/c\\ serverlessv2_scaling_configuration = {' terragrunt.hcl"

              }

              sh "cat terragrunt.hcl"
              sh "terragrunt hclfmt || true"
              dir("$TARGET_ENV_IAC_DIR/rds_aurora") {

                def branchName = "automation-aurora-switch-${TARGET_ENVIRONMENT_NAME}"
                def commitMsg  = "feat: switch aurora from ${CURRENT_ENGINE_MODE} to ${targetEngineMode} ${TARGET_ENVIRONMENT_NAME}"
                def targetDir = "$TARGET_ENV_IAC_DIR/rds_aurora"
                //To-Do: Consider using push force or recreate unique branch everytime and risk poluting the repo
                pushChanges(branchName, commitMsg, targetDir)


                def prSummary= "Switch aurora from ${CURRENT_ENGINE_MODE} to ${targetEngineMode} ${TARGET_ENVIRONMENT_NAME}"
                def repoUrl = "https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests"
                def prLink = createPullRequests(repoUrl, branchName, commitMsg, prSummary)
                successJobReply( ":bitbucket: Pull Request created <${prLink}|here>" )
              }

              runTerragrunt("plan -out=plan.out")

              successJobReply( ":terraform: Terragrunt plan is out. Need review and approval before applying :point_right: <${env.BUILD_URL}console|*View Jenkins Console Output*>" )
              timeout(time: 45, unit: 'MINUTES') {
                  input(
                      id: 'userInputTG', 
                      message: 'Please review the plan output before proceeding. Apply the changes?', 
                  )
              }
          
              runTerragrunt("apply -auto-approve plan.out")

            }
          }  
          
        }
        script {
          // Update with your message here
          successJobReply( "IaC reconciliation is done!" )
        }
      }
    }

    //To-Do: Cleanup will fail if we merged the PR
    stage('Cleanup temp instance') {
      when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' && (params.RUN_CLEANUP_ONLY || !params.RUN_RECONCILE_ONLY) } }
      steps {
        dir("$WORKSPACE/hosting/pipeline/aurora-provisioned-x-serverless-switch") {
          script {
            def baseName = env.AURORA_CLUSTER_ID[0..-9]
            def tempInstanceID = "${baseName}-temp"

            def rawJson = sh(
                script: """
                  aws rds describe-db-instances \
                    --query "DBInstances[?DBClusterIdentifier=='${AURORA_CLUSTER_ID}']" \
                    --output json
                """,
                returnStdout: true
            ).trim()

            def parsed = readJSON text: rawJson
            def nonMatching = []

            for (inst in parsed) {
                def instanceId = inst.DBInstanceIdentifier
                def dbClass = inst.DBInstanceClass
                def mode = dbClass?.contains("serverless") ? "serverless" : "provisioned"
                if (mode != params.TARGET_ENGINE_MODE) {
                    nonMatching << instanceId
                }

                if ( instanceId == tempInstanceID ) {
                    nonMatching << instanceId
                }
            }


            if (nonMatching.size() == 0) {
                echo "✅ All instances in cluster '${AURORA_CLUSTER_ID}' match desired mode: ${params.TARGET_ENGINE_MODE}"
            } else {
                def deleteTasks = [:]
                nonMatching.each { id ->
                    deleteTasks["Delete-${id}"] = {
                      echo "🧨 Deleting - ${id}"
                      sh "bash ./delete-instance.sh ${id}"
                    }
                }
                parallel deleteTasks
            }          
          }

        }
        script {
          // Update with your message here
          successJobReply( "Cleanup completed!" )
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


void runTerragruntStatePull() {
  sh "tfenv install"
  sh "AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt state pull > ./terraform.tfstate"
}

void runTerragruntWithYes(String command) {
  sh "tfenv install"
  sh "yes yes | AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt ${command}"
}


def getEnvironmentList() {
  def envData = []
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

def runTerragruntOutput() {
  sh "tfenv install"
  def output = sh(
                  script: "AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt output -json --terragrunt-fetch-dependency-output-from-state",
                  returnStdout: true
                ).trim()
  // def output = sh (returnStdout: true, script: """#!/bin/bash
  //               AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt run --experiment cli-redesign --dependency-fetch-output-from-state output ${outputName}
  //               """).trim()
  return output
}

String convertAuroraServerlessToProvisioned(String minAcuStr, String maxAcuStr) {
    double minAcu = minAcuStr.toDouble()
    double maxAcu = maxAcuStr.toDouble()

    def acuToInstanceClass = [
        ((0.5)..(2.0))     : 'db.t4g.medium',
        ((2.0)..(8.0))     : 'db.r6g.large',
        ((2.0)..(16.0))    : 'db.r6g.xlarge',
        ((2.0)..(32.0))    : 'db.r6g.2xlarge',
        ((2.0)..(64.0))    : 'db.r6g.4xlarge',
        ((2.0)..(128.0))   : 'db.r6g.8xlarge',
        ((2.0)..(192.0))   : 'db.r6g.12xlarge',
        ((2.0)..(256.0))   : 'db.r6g.16xlarge'
    ]

    for (range in acuToInstanceClass.keySet()) {
        if (range.containsWithinBounds(maxAcu)) {
            return acuToInstanceClass[range]
        }
    }

    throw new IllegalArgumentException("Unsupported ACU range: ${minAcuStr}-${maxAcuStr}")
}

void convertProvisionedToAuroraServerlessACU(String instanceType) {
    
    // Define RDS to Aurora Serverless ACU mapping
    def rdsToAuroraServerlessACU = [
      'db.t4g.medium'   : ['min': 2, 'max': 2],
      'db.m6g.large'    : ['min': 2, 'max': 8],
      'db.m6g.xlarge'   : ['min': 2, 'max': 16],
      'db.m6g.2xlarge'  : ['min': 2, 'max': 32],
      'db.m6g.4xlarge'  : ['min': 2, 'max': 64],
      'db.m6g.8xlarge'  : ['min': 2, 'max': 128],
      'db.m6g.12xlarge' : ['min': 2, 'max': 192],
      'db.m6g.16xlarge' : ['min': 2, 'max': 256]
        // Add other mappings as needed
    ]
    
    if (!rdsToAuroraServerlessACU.containsKey(instanceType)) {
        throw new IllegalArgumentException("Unsupported RDS instance type for Aurora Serverless: ${instanceType}")
    }

    return rdsToAuroraServerlessACU[instanceType]
}

String createPullRequests(String repoUrl, String branch, String commitMessage, String summary) {
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

        return prHtmlLink
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