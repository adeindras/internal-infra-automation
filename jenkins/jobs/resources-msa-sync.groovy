import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
    [
        parameters([
            string(defaultValue: '', name: 'targetCCU'),
            string(defaultValue: '', name: 'identifier'),
            string(defaultValue: '', name: 'targetEnvironmentName'),
            string(defaultValue: '', name: 'msaData'),
            string(defaultValue: '', name: 'slackThread')
        ])
    ]
)

// constants
TARGET_CCU = params.targetCCU
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName
TIMESTAMP = params.identifier
def identifier = params.identifier
def msaResourcesData = JsonOutput.toJson(params.msaData)

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def slackThread = params.slackThread
def branchIdentifier = ""
def prHtmlLink = "https://bitbucket.org/accelbyte/iac/src/master/" //dummy value
def slackFinalStatus = "SUCCESS"

String toolScriptDir = '.'

node('infra-sizing') {
    container('tool') {
        stage('Check Params'){
            if (WORKSPACE.contains("DEVELOPMENT")) {
                branchIdentifier = "DEVELOPMENT"
            }

            if (TARGET_CCU == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            if (TARGET_ENVIRONMENT_NAME == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            if (msaResourcesData == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            if (identifier == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            echo TARGET_CCU
            echo TARGET_ENVIRONMENT_NAME
        }

        if (buildStopped) {
            currentBuild.result = 'NOT_BUILT'
            return
        }

        try {
            // currentBuild.displayName = "#${TARGET_CCU} - ${TARGET_ENVIRONMENT_NAME} - ${BUILD_NUMBER}"

            stage('Preparing tools') {
                withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                    sh """
                        mkdir ${tempDir}
                        cd ${tempDir}
                        BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                            -d jenkins/jobs/scripts/autorightsizing \
                            -r master \
                            -s internal-infra-automation \
                            -o \$(pwd)
                        chown -R 1000:1000 jenkins
                    """
                }

                toolScriptDir = pwd() + '/' + tempDir + '/jenkins/jobs/scripts/autorightsizing'

                sh """
                    chmod +x ${toolScriptDir}/*.sh
                """
            }

            stage('Parse data and Modify Resources'){
                dir(tempDir) {
                    sshagent(['bitbucket-repo-read-only']) {
                        CLIENT_NAME = sh(returnStdout: true, script: """
                            echo "${TARGET_ENVIRONMENT_NAME}" | awk -F'-' '{print \$1}'
                        """
                        ).trim()
                        PROJECT_NAME = sh(returnStdout: true, script: """
                            echo "${TARGET_ENVIRONMENT_NAME}" | awk -F'-' '{print \$2}'
                        """
                        ).trim()
                        ENVIRONMENT_NAME = sh(returnStdout: true, script: """
                            echo "${TARGET_ENVIRONMENT_NAME}" | awk -F'-' '{print \$3}'
                        """
                        ).trim()

                        // Clone IAC repo
                        sh """#!/bin/bash
                        set -e
                        export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                        git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/iac.git"
                        cd iac
                        IAC_REPO_DIR=\$(pwd)
                        TG_DIRECTORY=\$(find | grep -E '${CLIENT_NAME}\\/${PROJECT_NAME}\\/*\\/.*\\/${ENVIRONMENT_NAME}\\/eks\$' | sed 's/eks//g')
                        """

                        AWS_ACCOUNT_ID = sh(returnStdout: true, script: """
                            #!/bin/bash
                            cd iac
                            TG_DIRECTORY=\$(find | grep -E '${CLIENT_NAME}\\/${PROJECT_NAME}\\/*\\/.*\\/${ENVIRONMENT_NAME}\\/eks\$' | sed 's/eks//g')
                            echo \${TG_DIRECTORY} | cut -f3 -d'/'
                        """
                        ).trim()

                        BB_BRANCH_NAME = sh(returnStdout: true, script: """
                            echo "autorightsizing-${AWS_ACCOUNT_ID}-${CLIENT_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-${TIMESTAMP}"
                        """
                        ).trim()

                        // Escape the environment variable
                        def escaped_resource_data = escapeParens(msaResourcesData)

                        encoded_resource_data = sh(returnStdout: true, script:"""
                            echo -ne "${escaped_resource_data}" | base64
                        """
                        ).trim()

                        echo "escaped_resource_data: ${escaped_resource_data}"

                        sh """
                            #!/bin/bash

                            if [[ ! -f ${toolScriptDir}/resource-msa-sync.sh ]]; then
                                echo "script not exist"
                                exit 1
                            fi

                            echo "script exist"
                            bash ${toolScriptDir}/resource-msa-sync.sh "${encoded_resource_data}" "${CLIENT_NAME}" "${PROJECT_NAME}" "${ENVIRONMENT_NAME}"
                        """

                        CHANGES_DETECTED = sh(returnStdout: true, script: """
                            #!/bin/bash
                            set -e
                            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                            cd iac

                            get_num_of_changes=\$(git diff --name-only | wc -l)
                            cd \${IAC_REPO_DIR} || exit 1

                            if [[ \$get_num_of_changes -ge 1 ]] && [[ ! -z \$get_num_of_changes ]]; then
                                echo "true"
                            else
                                echo "false"
                            fi
                        """
                        ).trim()

                        if (CHANGES_DETECTED == 'true') {
                            sh """#!/bin/bash
                            set -e
                            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                            cd iac

                            echo "Changes detected, creating branch ..."
                            git_branch_name="${branchIdentifier}autorightsizing-${AWS_ACCOUNT_ID}-${CLIENT_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-${TIMESTAMP}"
                            git checkout -b ${BB_BRANCH_NAME}
                            git config --global user.email "build@accelbyte.net"
                            git config --global user.name "Build AccelByte"
                            git add .
                            git commit -m "feat: ${CLIENT_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME} autorightsizing to ${TARGET_CCU} CCU"
                            git push --set-upstream origin ${BB_BRANCH_NAME}
                            """
                        }
                    }
                }
            }

            stage("Create PR") {
                withCredentials([string(credentialsId: "BitbucketAppKeyUserPassB64", variable: 'BitbucketAppKeyUserPassB64')]) {
                    if (CHANGES_DETECTED == 'true') {
                        // POST
                        def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
                        def postData =  [
                            title: "feat: Auto Rightsizing ${TARGET_ENVIRONMENT_NAME} to ${TARGET_CCU} CCU",
                            source: [
                                branch: [
                                    name: "${BB_BRANCH_NAME}"
                                ]
                            ],
                            reviewers:[],
                            destination: [
                                branch: [
                                    name: "master"
                                ]
                            ],
                            close_source_branch: true
                        ]
                        def jsonPayload = JsonOutput.toJson(postData)
                        post.setRequestMethod("POST")
                        post.setDoOutput(true)
                        post.setRequestProperty("Content-Type", "application/json")
                        post.setRequestProperty("Authorization", "Basic ${BitbucketAppKeyUserPassB64}")
                        post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                        def postRC = post.getResponseCode();
                        println(postRC);
                        if(postRC.equals(200) || postRC.equals(201)) {
                            def jsonSlurper = new JsonSlurper()
                            def reply = post.getInputStream().getText()
                            def replyMap = jsonSlurper.parseText(reply)
                            prHtmlLink = replyMap.links.html.href
                            println(replyMap);
                        }
                    }
                }
            }
        } catch (SkipException ex) {
            currentBuild.result = 'ABORTED'
            currentBuild.displayName = "${currentBuild.displayName} - [Skipped]"
            echo "Build is skipped:\n\t${ex.message}"
            slackFinalStatus = currentBuild.result
        } catch (InterruptedException err) {
            currentBuild.result = 'ABORTED'
            slackFinalStatus = currentBuild.result
        } catch (Exception err) {
            echo "Exception Thrown:\n\t${err}"
            currentBuild.result = 'FAILURE'
            slackFinalStatus = currentBuild.result
            // TODO: Send notif to slack channel
        } finally {
            stage('Sending Slack Notification'){
                def elapsedTime = currentBuild.durationString.replaceAll(' and counting', "")
                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: "C079A11910R",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: ":tf: ${slackFinalStatus} Sync AWS resources with MSA data job:"
                                ]
                            ],
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*Jenkins:*\n<${BUILD_URL}/console|Go to Jenkins!>"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*PR:*\n<${prHtmlLink}/console|Check PR!>"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Execution Time:*\n${elapsedTime}"
                                    ],
                                ]
                            ]
                        ],
                        thread_ts: "${slackThread}"
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
        }
    }
}

// Hacky way to skip later stages
public class SkipException extends Exception {
    public SkipException(String errorMessage) {
        super(errorMessage);
    }
}

def escapeParens(String input) {
    return input.replace("(", "\\(").replace(")", "\\)")
}