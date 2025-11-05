import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: ["tier-1","tier-2","tier-3","tier-4"].join("\n"), name: 'tierSetup', description: 'Tier template to use for check'),
            choice(choices: envList, description: "Environment to check", name: "targetEnvironmentName")
        ])
    ]
)

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

// constants
TIER_SETUP = params.tierSetup
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def resourceEnabled = true
def userId
def slackThread
def slackChannel

def ccuTierData() {
    tierToCCU = ""
    switch(TIER_SETUP){
        case "tier-1":
            tierToCCU = "<1k"
            break
        case "tier-2":
            tierToCCU = "1k - 20k"
            break
        case "tier-3":
            tierToCCU = "20k - 120k"
            break
        case "tier-4":
            tierToCCU = "500k - 1000k"
            break
        default:
            tierToCCU = "0"
            break
    }
    echo "${tierToCCU}"
    return tierToCCU
}


node('hosting-agent') {
    container('tool') {
        stage('Pipeline Pre-Check'){
            if (TIER_SETUP == '' || TIER_SETUP == 'blank' ) {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (TARGET_ENVIRONMENT_NAME == '' || TARGET_ENVIRONMENT_NAME == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            echo TIER_SETUP
            echo TARGET_ENVIRONMENT_NAME
            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07UY55SE20"
            } else {
                slackChannel = "C080SRE92NA"
            }
        }
        if (!buildStopped) {
            currentBuild.displayName = "#${BUILD_NUMBER} - ${TARGET_ENVIRONMENT_NAME} - ${TIER_SETUP}"
            userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
            stage('Get Tier Info'){
                withCredentials([string(credentialsId: "TierScaleEndpointURL", variable: 'tierScaleEndpointURL')]) {
                    sh """#!/bin/bash
                        set -eo pipefail
                        mkdir -p ${tempDir}
                        cd ${tempDir}
                        curl -sL \${tierScaleEndpointURL}?tier=${TIER_SETUP} -o ./tier_data.json
                        cat tier_data.json
                    """
                }
            }
            stage('Sending Slack Notification'){
                TIER_TO_CCU = ccuTierData();
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
                                    text: ":green-light-plus: POST SCALE VALIDATION :green-light-plus:"
                                ]
                            ], 
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*Tier Used:*\n${TIER_SETUP}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*CCU Setup:*\n${TIER_TO_CCU}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Environment:*\n${TARGET_ENVIRONMENT_NAME}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Triggered by:*\n${userId}"
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
            if (resourceEnabled) {
                stage('Infra Resource Check') {
                    dir(tempDir) {
                        def tierInfoJson = sh(returnStdout: true, quiet: true, script: "jq -r '.resources' --indent 0 tier_data.json").trim()
                        if (tierInfoJson){
                            build job: 'scaler-checker-process',
                            parameters: [
                                [$class: 'StringParameterValue', name: 'tierSetup', value: TIER_SETUP],
                                [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: TARGET_ENVIRONMENT_NAME],
                                [$class: 'StringParameterValue', name: 'tierInfo', value: tierInfoJson],
                                [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                            ],
                            wait: false
                        } else {
                            error("Invalid JSON data")
                        }
                    }
                }
            }
        }
    }
}
