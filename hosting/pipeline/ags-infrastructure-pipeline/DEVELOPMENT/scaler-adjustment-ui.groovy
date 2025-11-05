/* groovylint-disable LineLength */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
  [
    parameters([
      choice(choices: envList, name: 'targetEnvironmentName', description: 'target environment'),
      choice(choices: ["tier-1","tier-2","tier-3","tier-4"].join("\n"), name: 'tierSetup', description: 'tier-1 <1k || tier-2 1k-20k || tier-3 20k-120k || tier-4 500k-1000k')
    ])
  ]
)

String targetEnvironmentName = params.targetEnvironmentName
String tierSetup = params.tierSetup
String currentTier
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME
String iacBranchURL = "https://bitbucket.org/accelbyte/iac/branch"
String iacPrURL = "https://bitbucket.org/accelbyte/iac/pull-requests"
def prHtmlLink
def buildStopped = false
def userId
def slackThread
def slackChannel

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

node('hosting-agent') {
    container('tool') {
        stage('Pipeline Pre-Check'){
            if (tierSetup == '' || tierSetup == 'blank' ) {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (targetEnvironmentName == '' || targetEnvironmentName == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (targetEnvironmentName == '' || targetEnvironmentName == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            echo tierSetup
            echo targetEnvironmentName
            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07UY55SE20"
            } else {
                slackChannel = "C080SRE92NA"
            }
        }
        if (!buildStopped){
            currentBuild.displayName = "#${BUILD_NUMBER} - ${targetEnvironmentName} - ${tierSetup}"
            userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
            dir(tempDir){
                def (customer, project, environment) = targetEnvironmentName.split('-')
                stage('Clone iac repository') {
                    sshagent(['bitbucket-repo-read-only']) {
                        // Clone IAC repo
                        sh """#!/bin/bash
                            set -e
                            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                            git clone --quiet "git@bitbucket.org:accelbyte/iac.git" || true
                            rm -rf iacTemp || true
                            cp -R iac iacTemp || true
                            chmod -R 777 iacTemp || true
                        """
                    }
                }


                stage('Adjust tier manifest'){
                    dir('iacTemp'){
                        // Getting cluster directory information
                        iacDirectory = sh(returnStdout: true, script: """
                            clusterDir=\$(find live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep ${environment} | grep -v terragrunt-cache | head -n 1)
                            dirname \${clusterDir}
                        """
                        ).trim()
                        awsAccountId = sh(returnStdout: true, script: """
                            echo ${iacDirectory} | egrep -o '[[:digit:]]{12}'
                        """
                        ).trim()
                        awsRegion = sh(returnStdout: true, script: """
                            basename \$(dirname ${iacDirectory})
                        """
                        ).trim()

                        BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-${timeStamp}"

                        // Edit kustomization manifest
                        dir ("manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync/extended") {
                            currentTier = sh(returnStdout: true, script: ''' 
                                grep "tier" ags-infrastructure.yaml | awk -F'/' '{print $4}' | head -n 1
                            '''
                            ).trim()
                            sh """
                                sed -i 's/${currentTier}/${tierSetup}/g' ags-infrastructure.yaml
                            """
                        }

                        if (currentTier == tierSetup){
                            currentBuild = 'ABORTED'
                            error('Same tier setup. Exiting ...')
                        }
                    }
                }

                stage('Commit and push iac repo'){
                    sshagent(['bitbucket-repo-read-only']) {
                        sh """#!/bin/bash
                            set -e
                            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                            cp iacTemp/manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync/extended/ags-infrastructure.yaml iac/manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync/extended/ags-infrastructure.yaml
                            cd iac
                            chmod 644 manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync/extended/ags-infrastructure.yaml || true
                            git checkout -b ${BB_BRANCH_NAME}
                            git config --global user.email "build@accelbyte.net"
                            git config --global user.name "Build AccelByte"
                            git status
                            git add manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync/extended/ags-infrastructure.yaml
                            git commit -m "feat: ${BB_BRANCH_NAME}"
                            git push --set-upstream origin ${BB_BRANCH_NAME}
                        """
                    }
                }

                stage("Create PR iac repo") {
                    prSummary="""
    :: Infrastructure tier scaling adjustment \n \n
    :: Environment : ${targetEnvironmentName} \n \n
    :: Previous tier : ${currentTier} \n \n
    :: Changed tier : ${tierSetup} \n \n
    :: Warning! Might cause connection disruption and service restart \n \n
                    """
                    withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
                        // POST
                        def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
                        def postData =  [
                            title: "feat: infrastructure-tier-scaling ${targetEnvironmentName}",
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
                            summary: [
                                raw: "${prSummary}"
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
                stage('Sending Slack Notification'){
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        // POST
                        def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                        def postData =  [
                            channel: slackChannel,
                            blocks: [
                                [
                                    type: "section",
                                    text: [
                                        type: "mrkdwn",
                                        text: ":star: TIER ADJUSTMENT :star: \n*<${BUILD_URL}console|Go to Jenkins now!>*"
                                    ]
                                ], 
                                [
                                    type: "section",
                                    fields: [
                                        [
                                            type: "mrkdwn",
                                            text: "*New Tier:*\n${tierSetup}"
                                        ],
                                        [
                                            type: "mrkdwn",
                                            text: "*Current Tier:*\n${currentTier}"
                                        ],
                                        [
                                            type: "mrkdwn",
                                            text: "*Environment:*\n${targetEnvironmentName}"
                                        ],
                                        [
                                            type: "mrkdwn",
                                            text: "*Triggered by:*\n${userId}"
                                        ],
                                        [
                                            type: "mrkdwn",
                                            text: "*Branch:*\n<${iacBranchURL}/${BB_BRANCH_NAME}|${BB_BRANCH_NAME}>"
                                        ],
                                        [
                                            type: "mrkdwn",
                                            text: "*PR:*\n<${prHtmlLink}|Click Here>"
                                        ],
                                    ]
                                ]
                            ]
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
                            slackThread = replyMap.ts
                        }
                    }
                }
            }
        }
    }
}

