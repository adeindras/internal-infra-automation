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

def scalingBlueprintGetData(endpoint, apiKey) {
    def uri = "http://internal-scaling-blueprint.devportal/scalingblueprint" + endpoint
    def get = new URL(uri).openConnection();
    get.setRequestProperty ("Authorization", "Bearer " + apiKey);
    def getRC = get.getResponseCode();
    println(getRC);
    if(getRC.equals(200)) {
        def reply = get.getInputStream().getText()
        return reply
    }
    return null
}

def getEnvironmentList() {
    envs = []
    withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
        r = scalingBlueprintGetData("/admin/v1/clusters", ScalingBlueprintAPIKey)
        if ( r != null) {
            def jsonSlurper = new JsonSlurper()
            def replyMap = jsonSlurper.parseText(r)
            envs = replyMap.clusterNames
        }
    }

    if (!envs.find()) {
        envs.push("Error getting env list data")
    } else {
        return envs
    }
}

def getEnvironmentCCUList() {
    ccus = []
    withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
        r = scalingBlueprintGetData("/admin/v1/ccus", ScalingBlueprintAPIKey)
        if ( r != null) {
            def jsonSlurper = new JsonSlurper()
            def replyMap = jsonSlurper.parseText(r)
            ccus = replyMap.ccus
        }
    }

    if (!ccus.find()) {
        ccus.push("Error getting ccu list data")
    } else {
        return ccus
    }
}

// constants
def targetCCU = params.targetCCU
def targetEnvironmentName = params.targetEnvironmentName
def identifier = new Date().getTime().toString()

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def slackThread

node('infra-sizing') {
    container('tool') {
        stage('Check Params'){
            if (targetCCU == '' || targetCCU == 'blank' ) {
                buildStopped = true
            }
            if (targetEnvironmentName == '' || targetEnvironmentName == 'blank') {
                buildStopped = true
            }

            echo targetCCU
            echo targetEnvironmentName
        }
        if (!buildStopped) {
            currentBuild.displayName = "#${BUILD_NUMBER} - ${targetEnvironmentName} - ${targetCCU}"

            stage('Sending Slack Notification'){
                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: "C079A11910R",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: "You have a new autorightsizing request triggered by ${userId}:\n*<${BUILD_URL}/console|Go to Jenkins now!>*"
                                ]
                            ], 
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*CCU:*\n${targetCCU}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Environment:*\n${targetEnvironmentName}"
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

            stage('Trigger resources sync job') {
                withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
                    r = scalingBlueprintGetData("/v1/clusters/" + targetEnvironmentName + "/resources?ccu=" + targetCCU, ScalingBlueprintAPIKey)
                    if ( r != null) {
                        build job: 'Resources-MSA-Sync-v2',
                        parameters: [
                            [$class: 'StringParameterValue', name: 'targetCCU', value: targetCCU],
                            [$class: 'StringParameterValue', name: 'identifier', value: identifier],
                            [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: targetEnvironmentName],
                            [$class: 'StringParameterValue', name: 'msaData', value: r],
                            [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                        ],
                        wait: false
                    }
                }
            }

            stage('Trigger services sync job') {
                withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
                    r = scalingBlueprintGetData("/v1/clusters/" + targetEnvironmentName + "/services?ccu=" + targetCCU, ScalingBlueprintAPIKey)
                    if ( r != null) {
                        build job: 'Services-Overlay-MSA-Sync-v2',
                        parameters: [
                            [$class: 'StringParameterValue', name: 'targetCCU', value: targetCCU],
                            [$class: 'StringParameterValue', name: 'identifier', value: identifier],
                            [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: targetEnvironmentName],
                            [$class: 'StringParameterValue', name: 'msaData', value: r],
                            [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                        ],
                        wait: false
                    }
                }
            }
        }
    }
}
