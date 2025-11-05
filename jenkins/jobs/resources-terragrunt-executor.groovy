import jenkins.plugins.http_request.ResponseContentSupplier
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
  [
    parameters([
      string(defaultValue: '', name: 'awsAccountID'),
      string(defaultValue: '', name: 'directory'),
      string(defaultValue: '', name: 'clusterName'),
      string(defaultValue: '', name: 'actor'),
      string(defaultValue: '', name: 'branchName'),
      string(defaultValue: '', name: 'action'),
      string(defaultValue: '', name: 'commitHash'),
      string(defaultValue: '', name: 'slackThread'),
      string(defaultValue: '', name: 'autorightsizingModeEnabled')
    ])
  ]
)

// constants
DIRECTORY = params.directory
CLUSTER_NAME = params.clusterName
ACTOR = params.actor
AWS_ACCOUNT_ID = params.awsAccountID
ACTION = params.action
BRANCH_NAME = params.branchName
COMMIT_HASH = params.commitHash
AUTORIGHTSIZING_MODE_ENABLED = params.autorightsizingModeEnabled
String CUSTOMER_NAME
String PROJECT_NAME
String ENVIRONMENT_NAME
String MODULE_NAME
def slackThread = params.slackThread
def slackChannel

def tempDir = "tmpdir${BUILD_NUMBER}"
def RESOURCE_NAME = ""

podTemplate(yaml: """
    apiVersion: v1
    kind: Pod
    metadata:
      annotations:
        karpenter.sh/do-not-disrupt: "true"
    spec:
      serviceAccountName: jenkins-agent-${CLUSTER_NAME}
      containers:
        - name: tool
          image: 268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent:v1.0.0-infraapps-v4
          command:
            - cat
          tty: true
          securityContext:
            runAsUser: 0
          resources:
            requests:
              memory: 500Mi
              cpu: 250m
      imagePullSecrets:
        - ecr-credentials
      nodeSelector:
        karpenter.sh/capacity-type: on-demand
        karpenter.sh/nodepool: default
    """) {
    node(POD_LABEL) {
        container('tool') {
            try {
                stage('Init') {
                    createBanner("STAGE: Initializing.. sending status to bitbucket")

                    def delimiter = '/'
                    def startField = 7

                    def fields = DIRECTORY.split(delimiter)
                    if (fields.size() >= startField) {
                        RESOURCE_NAME = fields[startField - 1..-1].join(delimiter)
                    }

                    if (ACTION == 'apply --auto-approve') {
                        currentBuild.displayName = "apply - ${RESOURCE_NAME} - ${CLUSTER_NAME} - #${BUILD_NUMBER}"
                    } else {
                        currentBuild.displayName = "${ACTION} - ${RESOURCE_NAME} - ${CLUSTER_NAME} - #${BUILD_NUMBER}"
                    }

                    if (AUTORIGHTSIZING_MODE_ENABLED == "false") {
                        currentBuild.displayName = "jenkins - ${currentBuild.displayName}"
                    }

                    withCredentials([string(credentialsId: 'BitbucketIacAcceessTokenRO-0', variable: 'BITBUCKET_ACCESS_TOKEN')]) {
                        updateBitbucketStatus(COMMIT_HASH, 'INPROGRESS', BITBUCKET_ACCESS_TOKEN)
                    }
                }
                stage('Clone IAC'){
                    sshagent(['bitbucket-repo-read-only']) {
                        sh """#!/bin/bash
                            set -eo pipefail
                            mkdir -p ${tempDir}
                            cd ${tempDir}
                            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                            git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/iac.git" -b ${params.branchName}
                        """
                        // Getting Cluster Information
                        CUSTOMER_NAME = sh(returnStdout: true, script: """
                            echo "${CLUSTER_NAME}" | cut -d'-' -f1
                        """
                        ).trim()
                        PROJECT_NAME = sh(returnStdout: true, script: """
                            echo "${CLUSTER_NAME}" | cut -d'-' -f2
                        """
                        ).trim()
                        ENVIRONMENT_NAME = sh(returnStdout: true, script: """
                            echo "${CLUSTER_NAME}" | cut -d'-' -f3
                        """
                        ).trim()
                        REGION_NAME = sh(returnStdout: true, script: """
                            echo "${DIRECTORY}" | cut -d'/' -f5
                        """
                        ).trim()
                        MODULE_NAME = sh(returnStdout: true, script: """
                            echo "${DIRECTORY}" | cut -d'/' -f7
                        """
                        ).trim()
                    }
                }
                stage('Modify infra size'){
                    echo DIRECTORY
                    echo CLUSTER_NAME
                    echo ACTOR
                    echo AWS_ACCOUNT_ID
                    echo ACTION
                    dir(tempDir){
                        withCredentials([
                            file(credentialsId: 'sops-key', variable: 'SOPS_AGE_KEY_FILE'),
                            usernamePassword(credentialsId: 'iac-wiz-service-account', usernameVariable: 'CLIENT_ID', passwordVariable: 'CLIENT_SECRET')
                        ]){
                            // Preparing tools and apply sso_roles
                            sh """
                                mkdir ${WORKSPACE}/${tempDir}/wizResult
                                curl --silent -o ${WORKSPACE}/wizcli https://downloads.wiz.io/wizcli/latest/wizcli-linux-amd64 && chmod +x ${WORKSPACE}/wizcli
                                ${WORKSPACE}/wizcli version
                                ${WORKSPACE}/wizcli auth --id ${CLIENT_ID} --secret ${CLIENT_SECRET}
                                cd iac/${DIRECTORY}
                                tgenv install
                                rm -rf ~/.aws/config || true
                                echo "Performing terragrunt apply..."
                                terraform --version
                                set -x
                                cd ${WORKSPACE}/${tempDir}/iac/live/${AWS_ACCOUNT_ID}/${CUSTOMER_NAME}/${PROJECT_NAME}/${REGION_NAME}/${ENVIRONMENT_NAME}/sso_roles
                                terragrunt apply -auto-approve
                                set +x
                            """
                            // Terragrunt plan conditions
                            if(ACTION.equals("plan") ) {
                                if (DIRECTORY.contains("rds_aurora/provisioned")){
                                    withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {                
                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/whitelist-gate-for-aurora-provisioned.sh \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                            chmod +x /tmp/${BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/whitelist-gate-for-aurora-provisioned.sh
                                        """

                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/aurora-provisioned-whitelist.json \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                        """
                                    }
                                }

                                def auroraProvisionedChangesWhitelistGateValidationScript = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/whitelist-gate-for-aurora-provisioned.sh"
                                def auroraProvisionedChangesWhitelistGateFile = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/aurora-provisioned-whitelist.json"

                                withEnv([
                                    "SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_VALIDATION_SCRIPT_PATH=${auroraProvisionedChangesWhitelistGateValidationScript}",
                                    "SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_FILE_PATH=${auroraProvisionedChangesWhitelistGateFile}",
                                    "SHELL_DIRECTORY=${DIRECTORY}",
                                    "SHELL_AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}",
                                    "SHELL_WORKSPACE=${WORKSPACE}",
                                    "SHELL_TEMPDIR=${tempDir}",
                                    "SHELL_ACTION=${ACTION}",
                                    "SHELL_MODULE_NAME=${MODULE_NAME}",
                                    "SHELL_CLUSTER_NAME=${CLUSTER_NAME}",
                                    "AWS_REGION=${REGION_NAME}",
                                    "AWS_DEFAULT_REGION=${REGION_NAME}"
                                ]) {
                                    sh '''
                                        cd "iac/$SHELL_DIRECTORY"
                                        terraform --version
                                        terragrunt init || true
                                        
                                        # case of rds aurora provisioned
                                        if [[ "$SHELL_DIRECTORY" == *"rds_aurora/provisioned"* ]]; then
                                            # this will be executed if the changes is from rds_aurora/provisioned resource
                    
                                            if [[ ! -x "$SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_VALIDATION_SCRIPT_PATH" ]]; then
                                                echo "Error: Script changes validation script path not found or not executable!"
                                                exit 1
                                            fi
                        
                                            # check is there any changes not on the approved whitelist?
                                            # the script would return false if it detect at least one unwhitelisted changes
                                            # output:
                                            # - true: continue
                                            # - false: abort with error message
                                            #          example: [ERROR] Change to 'db_subnet_group_name', 'db_parameter_group_name' detected. This requires a manual migration plan. Aborting.
                                            
                                            if ! "$SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_VALIDATION_SCRIPT_PATH" "$SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_FILE_PATH"; then
                                                exit 1
                                            fi
                                        fi

                                        echo "Performing terragrunt plan..."
                                        set -x
                                        terragrunt $SHELL_ACTION -out $SHELL_WORKSPACE/$SHELL_TEMPDIR/wizResult/plan.cache || true
                                        terragrunt show -json $SHELL_WORKSPACE/$SHELL_TEMPDIR/wizResult/plan.cache > $SHELL_WORKSPACE/$SHELL_TEMPDIR/wizResult/plan.json || true
                                        $SHELL_WORKSPACE/wizcli iac scan \
                                            --path $SHELL_WORKSPACE/$SHELL_TEMPDIR/wizResult/plan.json \
                                            --name "Terragrunt Executor $SHELL_CLUSTER_NAME/$SHELL_MODULE_NAME" \
                                            --format json \
                                            --output $SHELL_WORKSPACE/$SHELL_TEMPDIR/wizResult/results.json,json,true \
                                            --tag "$SHELL_CLUSTER_NAME,iac,jenkins,$SHELL_MODULE_NAME"
                                        set +x
                                    '''
                                }
                            } else { // Terragrunt apply conditions
                                if (DIRECTORY.contains("docdb")){
                                    withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/docdb-non-writer-apply.sh \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                            chmod +x /tmp/${BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/docdb-non-writer-apply.sh
                                        """
                                    }
                                }

                                if (DIRECTORY.contains("rds_aurora/provisioned")){
                                    withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/rollout-aurora-provisioned-new-instance-class.sh \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                            chmod +x /tmp/${BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/rollout-aurora-provisioned-new-instance-class.sh
                                        """
                
                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f apps/rightsize-hcl-editor/rightsize-hcl-editor \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                            chmod +x /tmp/${BUILD_NUMBER}/apps/rightsize-hcl-editor/rightsize-hcl-editor
                                        """
                
                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/validate-aurora-provisioned-changes.sh \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                            chmod +x /tmp/${BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/validate-aurora-instance-class.sh
                                        """
                
                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/whitelist-gate-for-aurora-provisioned.sh \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                            chmod +x /tmp/${BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/whitelist-gate-for-aurora-provisioned.sh
                                        """

                                        sh """
                                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                                -f jenkins/jobs/scripts/rightsizing/aurora-provisioned-whitelist.json \
                                                -r master \
                                                -s internal-infra-automation \
                                                -o /tmp/${BUILD_NUMBER}
                                        """
                                    }
                                }

                                def auroraProvisionedChangesWhitelistGateValidationScript = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/whitelist-gate-for-aurora-provisioned.sh"
                                def auroraProvisionedChangesWhitelistGateFile = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/aurora-provisioned-whitelist.json"
                                def auroraProvisionedInstanceClassValidationScript = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/validate-aurora-instance-class.sh"
                                def auroraProvisionedRolloutNewInstaceClassScript = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/rollout-aurora-provisioned-new-instance-class.sh"
                                def rightsizeHclEditor = "/tmp/${env.BUILD_NUMBER}/apps/rightsize-hcl-editor/rightsize-hcl-editor"
                                def docdbScript = "/tmp/${env.BUILD_NUMBER}/jenkins/jobs/scripts/rightsizing/docdb-non-writer-apply.sh"

                                withEnv([
                                    "SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_VALIDATION_SCRIPT_PATH=${auroraProvisionedChangesWhitelistGateValidationScript}",
                                    "SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_FILE_PATH=${auroraProvisionedChangesWhitelistGateFile}",
                                    "SHELL_AURORA_PROVISIONED_INSTANCE_CLASS_VALIDATION_SCRIPT_PATH=${auroraProvisionedInstanceClassValidationScript}",
                                    "SHELL_AURORA_PROVISIONED_ROLLOUT_NEW_INSTANCE_CLASS_SCRIPT_PATH=${auroraProvisionedRolloutNewInstaceClassScript}",
                                    "SHELL_RIGHTSIZE_HCL_EDITOR_PATH=${rightsizeHclEditor}",
                                    "SHELL_DIRECTORY=${DIRECTORY}",
                                    "SHELL_DOCDB_SCRIPT_PATH=${docdbScript}",
                                    "SHELL_AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}",
                                    "SHELL_WORKSPACE=${WORKSPACE}",
                                    "SHELL_TEMPDIR=${tempDir}",
                                    "SHELL_ACTION=${ACTION}",
                                    "AWS_REGION=${REGION_NAME}",
                                    "AWS_DEFAULT_REGION=${REGION_NAME}"
                                ]) {
                                    sh '''
                                        cd "iac/$SHELL_DIRECTORY"
                                        set -x
                                        terraform --version
                                        terragrunt init || true
                    
                                        # case of rds aurora provisioned
                                        if [[ "$SHELL_DIRECTORY" == *"rds_aurora/provisioned"* ]]; then
                                            # this will be executed if the changes is from rds_aurora/provisioned resource
                    
                                            if [[ ! -x "$SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_VALIDATION_SCRIPT_PATH" ]]; then
                                                echo "Error: Script changes validation script path not found or not executable!"
                                                exit 1
                                            fi
                        
                                            # check is there any changes not on the approved whitelist?
                                            # the script would return false if it detect at least one unwhitelisted changes
                                            # output:
                                            # - true: continue
                                            # - false: abort with error message
                                            #          example: [ERROR] Change to 'db_subnet_group_name', 'db_parameter_group_name' detected. This requires a manual migration plan. Aborting.
                                            
                                            if ! "$SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_VALIDATION_SCRIPT_PATH" "$SHELL_AURORA_PROVISIONED_CHANGES_WHITELIST_GATE_FILE_PATH"; then
                                                exit 1
                                            fi
                    
                                            if [[ ! -x "$SHELL_AURORA_PROVISIONED_INSTANCE_CLASS_VALIDATION_SCRIPT_PATH" ]]; then
                                                echo "Error: Script instance class validation not found or not executable!"
                                                exit 1
                                            fi
                    
                                            # check is there any "update" operation on "instance_class" attribute?
                                            # the script would return false if it detect at least one unwhitelisted changes
                                            # output:
                                            # - true: execute aurora-provisioned rightsizing script
                                            # - false: run terragrunt apply (continue)
                                            
                                            if "$SHELL_AURORA_PROVISIONED_INSTANCE_CLASS_VALIDATION_SCRIPT_PATH"; then
                                                # this will be executed if changes contain "update" operation on "instance_class" attribute
                                                show_output=$(mktemp)
                    
                                                echo "running aurora-provisioned rightsizing script.."
                    
                                                sed -i 's/inputs = {/inputs {/' terragrunt.hcl  
                                                instance_class="$("$SHELL_RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instance_class | tr -d '"')"
                                                sed -i 's/inputs {/inputs = {/' terragrunt.hcl
                    
                                                terragrunt show -json "$@" | sed '/\\[INFO\\]/d' > "${show_output}"
                                                cluster_id="$(jq -r '.values.root_module.child_modules[].resources[] | select(.type == "aws_rds_cluster") | .values.id' "${show_output}")"
                                                "$SHELL_AURORA_PROVISIONED_ROLLOUT_NEW_INSTANCE_CLASS_SCRIPT_PATH" \
                                                    --account-id "$SHELL_AWS_ACCOUNT_ID" \
                                                    --instance-class "$instance_class" \
                                                    --region "$AWS_REGION" \
                                                    --cluster "$cluster_id" \
                                                    --path "$SHELL_WORKSPACE/$SHELL_TEMPDIR/iac/$SHELL_DIRECTORY" \
                                                    --rightsize-hcl-editor-path "$SHELL_RIGHTSIZE_HCL_EDITOR_PATH"
                                            fi
                                        fi

                                        # case of documentdb
                                        if [[ "$SHELL_DIRECTORY" == *"docdb"* ]]; then
                                            echo "Performing apply on docdb..."
                                            "$SHELL_DOCDB_SCRIPT_PATH"
                                        fi
                    
                                        terragrunt $SHELL_ACTION
                                        set +x
                                    '''
                                }
                            }
                        }
                    }
                }
            } catch (SkipException ex) {
                currentBuild.result = 'ABORTED'
                currentBuild.displayName = "${currentBuild.displayName} - [Skipped]"
                echo "Build is skipped:\n\t${ex.message}"
            } catch (InterruptedException err) {
                currentBuild.result = 'ABORTED'
            } catch (Exception err) {
                echo "Exception Thrown:\n\t${err}"
                currentBuild.result = 'FAILURE'
                // TODO: Send notif to slack channel
            } finally {
                stage('Notification') {
                    def elapsedTime = currentBuild.durationString.replaceAll(' and counting', "")
                    withCredentials([string(credentialsId: 'BitbucketIacAcceessTokenRO-0', variable: 'BITBUCKET_ACCESS_TOKEN')]) {
                        updateBitbucketStatus(COMMIT_HASH, currentBuild.currentResult, BITBUCKET_ACCESS_TOKEN)
                    }
                    if (AUTORIGHTSIZING_MODE_ENABLED == "true") {
                        slackChannel="C017L2M1C3D" // #report-infra-changes
                    } else {
                        slackChannel=""
                        slackThread=""
                    }
                    def buildStatus=currentBuild.currentResult
                    def slackEmoji=":checked-1:"
                    if (buildStatus == "FAILURE") {
                        slackEmoji=":x:"
                    }
                    if (ACTION == "plan" && buildStatus != "SUCCESS") {
                        slackChannel="C0722TSBW7M" // #temp-tier-up-down-initiative
                        slackThread=""
                    }
                    def postData =  [
                        channel: "${slackChannel}",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: "${slackEmoji} ${DIRECTORY} apply job is done with ${currentBuild.currentResult} ${slackEmoji}\n"
                                ]
                            ],
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*Jenkins:*\n<${BUILD_URL}/console|Go to Jenkins!>"
                                    ],
                                ]
                            ],
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*Execution Time:*\n${elapsedTime}"
                                    ],
                                ]
                            ]
                        ],
                        thread_ts: "${slackThread}"
                    ]
                    
                    if (!(ACTION == "plan" && buildStatus == "SUCCESS")) {
                        updateInfraChangesSlackThread(postData)
                    }
                }
            }
        }
    }
}

void updateInfraChangesSlackThread(Map postData){
    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
        // POST
        def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
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

void updateBitbucketStatus(String commitHash, status, token) {
    String bitbucketURL = "https://api.bitbucket.org/2.0/repositories"
    String bitbucketState

    // Map jenkins result to bitbucket state
    // https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/
    switch(status) {
        case "SUCCESS":
            bitbucketState = "SUCCESSFUL"
            break
        case "INPROGRESS":
            bitbucketState = "INPROGRESS"
            break
        case "FAILURE":
            bitbucketState = "FAILED"
            break
        case "ABORTED":
            bitbucketState = "STOPPED"
            break
        default:
            throw new Exception("Unknown Jenkins build status: $status")
    }

    String requestBody = """
        {
            "key": "JENKINS-EXECUTION-${BUILD_NUMBER}",
            "name": "${currentBuild.displayName}",
            "state": "$bitbucketState",
            "description": "Jenkins Execution Pipeline",
            "url": "${BUILD_URL}console"
        }
    """

    httpRequest(
        url: "${bitbucketURL}/accelbyte/iac/commit/${commitHash}/statuses/build",
        httpMode: "POST",
        requestBody: requestBody,
        contentType: "APPLICATION_JSON",
        customHeaders: [[name: 'Authorization', value: 'Bearer ' + token]],
    )
}

// Hacky way to skip later stages
public class SkipException extends Exception {
    public SkipException(String errorMessage) {
        super(errorMessage);
    }
}


void createBanner(String message) {
    ansiColor('xterm'){
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
        echo '\033[1;4;33m' + ":: ${message}"
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    }
}