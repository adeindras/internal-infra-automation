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

// constants
TARGET_CCU = params.targetCCU
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName
SLACK_THREAD = params.slackThread

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def userId

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

node('infra-sizing') {
    container('tool') {
        stage('Check Params'){
            echo TARGET_CCU
            echo TARGET_ENVIRONMENT_NAME
            echo SLACK_THREAD

            if (TARGET_CCU == '' || TARGET_CCU == 'default' ) {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            if (TARGET_ENVIRONMENT_NAME == '' || TARGET_ENVIRONMENT_NAME == 'default') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            if (SLACK_THREAD == '' || SLACK_THREAD == 'default') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07C69NHGTW"
            } else {
                slackChannel = "C079A11910R"
            }
        }
        if (!buildStopped) {
            currentBuild.displayName = "#${BUILD_NUMBER} - ${TARGET_ENVIRONMENT_NAME} - ${TARGET_CCU}"
            userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']

            stage('Trigger Resource Validation Job') {
                withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
                    r = scalingBlueprintGetData("/v1/clusters/" + targetEnvironmentName + "/resources?ccu=" + targetCCU, ScalingBlueprintAPIKey)
                    if ( r != null) {
                        def data
                        withEnv(["JSON_DATA=${r}"]) {
                            data = sh(
                                returnStdout: true, 
                                quiet: true, 
                                script: '''
                                    echo "$JSON_DATA" | jq -r '.resources' --indent 0
                                '''
                            ).trim()
                        }

                        build job: 'Validation-Resources-v2',
                        parameters: [
                            [$class: 'StringParameterValue', name: 'targetCCU', value: TARGET_CCU],
                            [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: TARGET_ENVIRONMENT_NAME],
                            [$class: 'StringParameterValue', name: 'msaData', value: data],
                            [$class: 'StringParameterValue', name: 'slackThread', value: SLACK_THREAD],
                        ],
                        wait: false
                    }
                }
            }

            stage('Trigger Services Validation Job'){
                withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
                    r = scalingBlueprintGetData("/v1/clusters/" + targetEnvironmentName + "/services?ccu=" + targetCCU, ScalingBlueprintAPIKey)
                    if ( r != null) {
                        def data
                        withEnv(["JSON_DATA=${r}"]) {
                            data = sh(
                                returnStdout: true, 
                                quiet: true, 
                                script: '''
                                    echo "$JSON_DATA" | jq -r '.services' --indent 0
                                '''
                            ).trim()
                        }

                        build job: 'Validation-Services-Overlay-Dispatcher-v2',
                        parameters: [
                            [$class: 'StringParameterValue', name: 'targetCCU', value: TARGET_CCU],
                            [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: TARGET_ENVIRONMENT_NAME],
                            [$class: 'StringParameterValue', name: 'msaData', value: data],
                            [$class: 'StringParameterValue', name: 'userId', value: userId],
                            [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                        ],
                        wait: false
                    }
                }
            }
        }
    }
}
