import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
    [
        parameters([
            string(name: 'targetCCU', defaultValue: 'default'),
            string(name: 'targetEnvironmentName', defaultValue: 'default'),
            string(name: 'slackThread', defaultValue: 'default')
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

// constants
def targetCCU = params.targetCCU
def targetEnvironmentName = params.targetEnvironmentName
def slackThread = params.slackThread
def identifier = new Date().getTime().toString()

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false

node('infra-sizing') {
    container('tool') {
        stage('Check Params'){
            if (targetCCU == '' || targetCCU == 'default' ) {
                buildStopped = true
            }

            if (targetEnvironmentName == '' || targetEnvironmentName == 'default') {
                buildStopped = true
            }

            if (slackThread == '' || slackThread == 'default') {
                buildStopped = true
            }

            echo targetCCU
            echo targetEnvironmentName
            echo slackThread
        }
        if (!buildStopped) {
            currentBuild.displayName = "#${BUILD_NUMBER} - ${targetEnvironmentName} - ${targetCCU}"

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
