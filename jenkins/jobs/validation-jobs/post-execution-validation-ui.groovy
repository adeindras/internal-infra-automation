import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
envCCUList = getEnvironmentCCUList()
properties(
    [
        parameters([
            choice(choices: envCCUList, description: "", name: "targetCCU"),
            choice(choices: envList, description: "", name: "targetEnvironmentName")
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
        envData.push("Error getting env list data")
    } else {
        return envData
    }
}

def getEnvironmentCCUList() {
    envCCUData = []
    withCredentials([string(credentialsId: "AppScriptEndpointUrl", variable: 'appScriptEndpointUrl')]) {
        def get = new URL(appScriptEndpointUrl + "?q=sharedEnvCCUList").openConnection();
        def getRC = get.getResponseCode();
        println(getRC);
        if(getRC.equals(200)) {
            def jsonSlurper = new JsonSlurper()
            def reply = get.getInputStream().getText()
            def replyMap = jsonSlurper.parseText(reply)
            envCCUData = replyMap
        }
    }

    if (!envCCUData.find()) {
        envCCUData.push("Error getting env list data")
    } else {
        return envCCUData
    }
}

// constants
TARGET_CCU = params.targetCCU
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def resourceEnabled = true
def userId
def slackThread
def slackChannel

node('infra-sizing') {
    container('tool') {
        stage('Check Params'){
            if (TARGET_CCU == '' || TARGET_CCU == 'blank' ) {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (TARGET_ENVIRONMENT_NAME == '' || TARGET_ENVIRONMENT_NAME == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            TARGET_CCU = TARGET_CCU.split(" - ")[1]

            echo TARGET_CCU
            echo TARGET_ENVIRONMENT_NAME
            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07C69NHGTW"
            } else {
                slackChannel = "C079A11910R"
            }
        }
        if (!buildStopped) {
            currentBuild.displayName = "#${BUILD_NUMBER} - ${TARGET_ENVIRONMENT_NAME} - ${TARGET_CCU}"
            userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
            stage('Get CCU Data'){
                withCredentials([string(credentialsId: "AppScriptEndpointUrl", variable: 'appScriptEndpointUrl')]) {
                    sh """#!/bin/bash
                        set -eo pipefail
                        mkdir -p ${tempDir}
                        cd ${tempDir}
                        curl -sL \${appScriptEndpointUrl}?ccu=${TARGET_CCU} -o ./data.json
                    """
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
                                    text: ":checking: Autorightsizing Environment Validation:\n*<${BUILD_URL}console|Go to Jenkins now!>*"
                                ]
                            ], 
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*CCU:*\n${TARGET_CCU}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Environment:*\n${TARGET_ENVIRONMENT_NAME}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Triggered by:*\n${userId}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Note:*\nthis job is validating the current Infrastructure setup with the MSA document. make sure you're familiar with IAC repo and MSA document to understand the errors better."
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
                stage('Parse Resources Data') {
                    dir(tempDir) {
                        def msaDataResourcesJson = sh(returnStdout: true, quiet: true, script: "jq -r '.resources' --indent 0 data.json").trim()
                        if (msaDataResourcesJson){
                            build job: 'Validation-Resources',
                            parameters: [
                                [$class: 'StringParameterValue', name: 'targetCCU', value: TARGET_CCU],
                                [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: TARGET_ENVIRONMENT_NAME],
                                [$class: 'StringParameterValue', name: 'msaData', value: msaDataResourcesJson],
                                [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                            ],
                            wait: false
                        } else {
                            error("Invalid JSON data")
                        }
                    }
                }
            }
            stage('Parse Services Data'){
                dir(tempDir) {
                    msaDataServicesJson = sh(returnStdout: true, quiet: true, script: "jq -r '.services' --indent 0 data.json").trim()
                    if (msaDataServicesJson){
                        build job: 'Validation-Services-Overlay-Dispatcher',
                        parameters: [
                            [$class: 'StringParameterValue', name: 'targetCCU', value: TARGET_CCU],
                            [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: TARGET_ENVIRONMENT_NAME],
                            [$class: 'StringParameterValue', name: 'msaData', value: msaDataServicesJson],
                            [$class: 'StringParameterValue', name: 'userId', value: userId],
                            [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                        ],
                        wait: false
                    } else {
                        error("No data")
                    }
                }
            }
            stage('Functional Test'){
                dir(tempDir){
                    build job: 'Functional-Test',
                    parameters: [
                        [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: TARGET_ENVIRONMENT_NAME],
                        [$class: 'StringParameterValue', name: 'agsNamespace', value: 'AccelbyteTesting'],
                        [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                    ],
                    wait: false
                }
            }
        }
    }
}
