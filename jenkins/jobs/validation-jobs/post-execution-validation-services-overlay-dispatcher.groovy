import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
    [
        parameters([
            string(defaultValue: '', name: 'targetCCU'),
            string(defaultValue: '', name: 'targetEnvironmentName'),
            string(defaultValue: '', name: 'userId'),
            string(defaultValue: '', name: 'slackThread'),
            string(defaultValue: '', name: 'msaData')
        ])
    ]
)

// constants
BITBUCKET_CREDS_ID = 'bitbucket-repo-read-only'
DEPLOYMENT_REPO_SLUG = "deployments"
TARGET_CCU = params.targetCCU
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName
USER_ID = params.userId
def msaServicesOverlayData = JsonOutput.toJson(params.msaData)
String[] matchedMsaServicesOverlayData

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def latestMasterCommitHash = ""
def slackThread = params.slackThread
def slackChannel
def CLUSTER_PATH

node('deploy-agent') {
    container('tool') {
        stage('Check Params') {
            if (TARGET_CCU == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('targetCCU is empty. Aborting the build')
            }
            if (TARGET_ENVIRONMENT_NAME == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('targetEnvironmentName is empty. Aborting the build')
            }
            if (USER_ID == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('userId is empty. Aborting the build')
            }
            if (msaServicesOverlayData == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('msaData is empty. Aborting the build')
            }

            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07C69NHGTW"
            } else {
                slackChannel = "C079A11910R"
            }

            CLUSTER_PATH = TARGET_ENVIRONMENT_NAME.replace('-','/')

            echo "TARGET_CCU: ${TARGET_CCU}"
            echo "TARGET_ENVIRONMENT_NAME: ${TARGET_ENVIRONMENT_NAME}"
            echo "CLUSTER_PATH: ${CLUSTER_PATH}"

        }
        if (!buildStopped) {
            // stage('Sending Slack Notification'){
            //     withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            //         // POST
            //         def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
            //         def postData =  [
            //             channel: slackChannel,
            //             blocks: [
            //                 [
            //                     type: "section",
            //                     text: [
            //                         type: "mrkdwn",
            //                         text: "You have a new post-execution services-overlay validation request triggered by ${USER_ID}:\n*<${BUILD_URL}console|Go to Jenkins now!>*"
            //                     ]
            //                 ], 
            //                 [
            //                     type: "section",
            //                     fields: [
            //                         [
            //                             type: "mrkdwn",
            //                             text: "*CCU:*\n${TARGET_CCU}"
            //                         ],
            //                         [
            //                             type: "mrkdwn",
            //                             text: "*Environment:*\n${TARGET_ENVIRONMENT_NAME}"
            //                         ],
            //                         [
            //                             type: "mrkdwn",
            //                             text: "*Triggered by:*\n${USER_ID}"
            //                         ],
            //                     ]
            //                 ]
            //             ]
            //         ]
            //         def jsonPayload = JsonOutput.toJson(postData)
            //         post.setRequestMethod("POST")
            //         post.setDoOutput(true)
            //         post.setRequestProperty("Content-Type", "application/json")
            //         post.setRequestProperty("Authorization", "Bearer ${slackToken}")
            //         post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
            //         def postRC = post.getResponseCode();
            //         println(postRC);
            //         if(postRC.equals(200) || postRC.equals(201)) {
            //             def jsonSlurper = new JsonSlurper()
            //             def reply = post.getInputStream().getText()
            //             def replyMap = jsonSlurper.parseText(reply)
            //             slackThread = replyMap.ts
            //         }
            //     }
            // }
            stage('Checkout cluster information') {
                createBanner("STAGE: Checkout cluster ${TARGET_ENVIRONMENT_NAME} information")
                withCredentials([string(credentialsId: "internal-deploy-tool-token-0", variable: 'bbAccessToken')]) {
                    def cmd = '''
                        # get latest commit from master
                        LATEST_MASTER_COMMIT_HASH="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/deployments/commits/master?pagelen=1" | jq -r '.values[0].hash')"
                        echo ${LATEST_MASTER_COMMIT_HASH}
                    '''
                    latestMasterCommitHash = sh(returnStdout: true, script: cmd).trim()
                }
                dir("deployments${BUILD_NUMBER}") {
                    withCredentials([
                        string(credentialsId: 'internal-deploy-tool-token-0', variable: 'TOKEN_0'),
                        string(credentialsId: 'internal-deploy-tool-token-1', variable: 'TOKEN_1'),
                        string(credentialsId: 'internal-deploy-tool-token-2', variable: 'TOKEN_2'),
                        string(credentialsId: 'internal-deploy-tool-token-3', variable: 'TOKEN_3')
                    ]) {
                        def buildNo = BUILD_NUMBER as int
                        def mod = (buildNo % 4)
                        String[] tokens = [TOKEN_0, TOKEN_1, TOKEN_2, TOKEN_3]
                        env.BITBUCKET_ACCESS_TOKEN=tokens[mod]
                        sh """
                            bitbucket-downloader -f $CLUSTER_PATH/cluster-information.env \
                                -r ${latestMasterCommitHash} \
                                -s ${DEPLOYMENT_REPO_SLUG} \
                                -o \$(pwd)

                            ls -ltrah
                            chmod -R 755 *
                        """
                    }
                }
            }
            stage('Get cluster information') {
                createBanner("STAGE: Get cluster information")
                // get from file cluster-information.env in each cluster directory
                dir("deployments${BUILD_NUMBER}/$CLUSTER_PATH") {
                    def fileContent = readFile('cluster-information.env').trim()
                    def lines = fileContent.tokenize('\n')
                    lines.each { line ->
                        def (key, value) = line.tokenize('=')
                        env."${key}" = "${value}"
                        echo "${key} = ${value}"
                    }
                }
            }
            stage("Generate Kubeconfig") {
                createBanner("STAGE: Assuming AWS role and Generate Kubeconfig")
                sh """#!/bin/bash
                    set -e
                    set -o pipefail
                    envsubst < ~/.aws/config.template > ~/.aws/config
                    # aws sts get-caller-identity
                    aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_REGION}
                """
            }
            stage('Get deployment manifest, parse msa data and validate') {
                dir("deployments${BUILD_NUMBER}") {
                    environmentDir = sh(
                        returnStdout: true, 
                        script: """
                            echo ${TARGET_ENVIRONMENT_NAME} | sed 's/-/\\//g'
                        """
                    ).trim()

                    def cmd = """#!/bin/bash
                        set +x
                        msaData=${msaServicesOverlayData}
                        deployedServices=()
                        foundInCluster=false
                        foundInMSA="0"

                        initMatchedServicesOverlayJSON() {
                            echo '[]' > matched.json
                        }

                        getDeploymentManifest() {
                            kubectl -n justice get deployments -o json > deployments.json
                        }

                        parseDeploymentManifest() {
                            while read -r LINE; do
                                deployedServices+=("\$LINE")
                            done < <(jq -r '.items[].metadata.name' deployments.json)
                        }

                        parseMatchAndWriteMsaData() {
                            echo \${msaData} | jq -cr '.[]' | while read -r LINE; do
                                SERVICE_NAME=\$(echo "\${LINE}" | jq -r '.name')

                                # for each services, check deployedService
                                for DEPLOYED_SERVICE in "\${deployedServices[@]}"; {
                                    if [[ "\$SERVICE_NAME" == "\$DEPLOYED_SERVICE" ]]; then
                                        foundInCluster=true
                                        break
                                    fi
                                }

                                if [[ "\$foundInCluster" == true ]]; then
                                    echo "\$(jq --argjson SERVICE \${LINE} '. += [\$SERVICE]' matched.json)" > matched.json
                                    echo "\$SERVICE_NAME" >> matchedServices.txt
                                    foundInCluster=false
                                else
                                    echo "\$SERVICE_NAME" >> notMatchedServices.txt
                                fi
                            done
                        }

                        checkServicesOutsideMSA() {
                            for DEPLOYED_SERVICE_NAME in "\${deployedServices[@]}"; {
                                foundInMSA=\$(grep "\${DEPLOYED_SERVICE_NAME}" matchedServices.txt > /dev/null; echo \$?)

                                if [[ \${foundInMSA} != "0" ]]; then
                                    echo "\$DEPLOYED_SERVICE_NAME" >> deployedButOutsideMSA.txt
                                fi
                            }
                        }

                        printMatchedMsaDataStdout() {
                            jq -cr '.[]' matched.json | while read -r LINE; do
                                echo "\$LINE"
                            done
                        }

                        main() {
                            initMatchedServicesOverlayJSON
                            getDeploymentManifest
                            parseDeploymentManifest
                            parseMatchAndWriteMsaData
                            printMatchedMsaDataStdout
                            checkServicesOutsideMSA
                        }

                        main
                    """

                    matchedMsaServicesOverlayData = sh(returnStdout: true, script: cmd).split('\n').collect{it.trim()}.findAll{it}

                    def cmdPrintNotMatch = """#!/bin/bash
                        main() {
                            if [[ -f deployedButOutsideMSA.txt ]]; then
                                cat deployedButOutsideMSA.txt | while read -r LINE; do
                                    echo "\$LINE"
                                done
                            else
                                echo "empty"
                            fi
                        }
                        
                        main
                    """
                    notMatchedMsaServicesOverlayData = sh(returnStdout: true, script: cmdPrintNotMatch).split('\n').collect{it.trim()}.findAll{it}
                }   
            }
            stage('Sending Initial Slack Notification'){
                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    def postData
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();

                    if (notMatchedMsaServicesOverlayData == 'empty') {
                        // notMatchedMsaServicesOverlayData variable is not empty
                        postData =  [
                            channel: slackChannel,
                            blocks: [
                                [
                                    type: "section",
                                    text: [
                                        type: "mrkdwn",
                                        text: ":k8s: Service Validation Status"
                                    ]
                                ]
                            ],
                            thread_ts: "${slackThread}"
                        ]
                    } else {
                        postData =  [
                            channel: slackChannel,
                            blocks: [
                                [
                                    type: "section",
                                    text: [
                                        type: "mrkdwn",
                                        text: ":k8s: Service Validation Status"
                                    ]
                                ],
                                [
                                    type: "section",
                                    fields: [
                                        [
                                            type: "mrkdwn",
                                            text: "*Following Services Deployed in Cluster but not Listed in MSA*\n$notMatchedMsaServicesOverlayData"
                                        ]
                                    ]
                                ]
                            ],
                            thread_ts: "${slackThread}"
                        ]
                    }

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
            stage('Dispatch Services') {
                matchedMsaServicesOverlayData.each { data ->
                    try {
                        def slackMessage
                        dataServiceName = sh(
                            returnStdout: true, 
                            script: """
                                set +x
                                echo '${data}' | jq -r '.name'
                            """
                        ).trim()

                        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                            // POST
                            def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                            def postData =  [
                                channel: slackChannel,
                                blocks: [
                                    [
                                        type: "rich_text",
                                        elements: [
                                            [
                                                type: "rich_text_list",
                                                style: "bullet",
                                                elements: [
                                                    [
                                                        type: "rich_text_section",
                                                        elements: [
                                                            [
                                                                type: "text",
                                                                text: "${dataServiceName} "
                                                            ],
                                                            [
                                                                type: "emoji",
                                                                name: "memo"
                                                            ]
                                                        ]
                                                    ]
                                                ]
                                            ]
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
                                def jsonSlurper = new JsonSlurper()
                                def reply = post.getInputStream().getText()
                                def replyMap = jsonSlurper.parseText(reply)
                                slackMessage = replyMap.message.ts
                            }
                        }

                        echo "Triggering validation job for service folder: $dataServiceName"
                        build job: "Validation-Services-Overlay-Validator",
                        parameters: [
                            [$class: 'StringParameterValue', name: 'clusterName', value: TARGET_ENVIRONMENT_NAME],
                            [$class: 'StringParameterValue', name: 'msaServiceData', value: data],
                            [$class: 'StringParameterValue', name: 'slackMessage', value: slackMessage]
                        ],
                        wait: false
                    } catch (Exception e) {
                        echo "Caught an exception: ${e.message}"
                        throw e
                    }
                }
            }
        }
    }
}

void createBanner(String message) {
    ansiColor('xterm'){
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
        echo '\033[1;4;33m' + ":: ${message}"
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    }
}