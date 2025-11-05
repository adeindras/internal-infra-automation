import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            choice(name: 'TEST_METHOD', choices: ['using-lobby', 'without-lobby'], description: 'Select test method'),
            string(name: 'GAME_NAMESPACE', defaultValue: 'accelbytetesting', description: 'Game namespace testing environment'),
            string(name: 'TEST_SERVICES', defaultValue: 'iam', description: 'Comma-separated test types: iam,social'),
            string(name: 'TEST_SCENARIO', defaultValue: 'test_iam', description: 'Comma-separated test types: test_iam,test_social'),
            string(name: 'NUMBER_OF_USER', defaultValue: '1', description: 'Number of user'),
            string(name: 'TEST_DURATION', defaultValue: '10m', description: 'Test duration'),
            string(name: 'MAX_BACKOFF_DURATION', defaultValue: '30', description: 'max backoff duration when doing connection retries'),
            string(name: 'PROMETHEUS_SERVER_URL', defaultValue: 'https://prometheus-prod-10-prod-us-central-0.grafana.net/api/prom/push', description: 'prometheus url for emitting server')
        ])
    ]
)

TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName
def tempDir = "tmpdir${BUILD_NUMBER}"
def workDir = "${tempDir}/jenkins/jobs/scripts/load-generation-tool/" 
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

try {
    node('load-generator-agent'){
        container('tool'){
            currentBuild.displayName = "#${BUILD_NUMBER} - ${TARGET_ENVIRONMENT_NAME}"
            stage('Prepare k6 Installation'){
                dir(tempDir){
                    sh 'k6 version || echo "k6 is NOT installed!"'
                    withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                        sh '''
                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                -d jenkins/jobs/scripts/load-generation-tool \
                                -r development \
                                -s internal-infra-automation \
                                -o $(pwd)
                            chmod -R 755 *
                        '''
                    }
                }
            }

            stage('Get Environment Details'){
                dir(tempDir){
                    def (customer, project, environment) = TARGET_ENVIRONMENT_NAME.split('-')
                    env.customer=customer
                    env.project=project
                    env.environment=environment
                    def basePath = "/eks/${customer}/${project}/${environment}"
                    def baseUrlParam = "${basePath}/justice_base_url"
                    def publisherNamespaceParam = "${basePath}/justice_publisher_namespace"
                    def adminEmailParam = "${basePath}/justice-iam-service/superuser"
                    def adminPasswordParam = "${basePath}/justice-iam-service/superuser_password"
                    def clientIdParam = "${basePath}/justice-iam-service/iam_client_id"
                    def clientIdSecretParam = "${basePath}/justice-iam-service/iam_client_secret"

                    withCredentials([string(credentialsId: 'internal-deploy-tool-token-0', variable: 'BitbucketToken')]) {
                        sh '''
                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                -f $customer/$project/$environment/cluster-information.env \
                                -r master \
                                -s deployments \
                                -o $(pwd)
                        '''
                    }
                    def fileContent = readFile("$customer/$project/$environment/cluster-information.env").trim()
                    def lines = fileContent.tokenize('\n')
                    lines.each { line ->
                        def (key, value) = line.tokenize('=')
                        env."${key}" = "${value}"
                        echo "${key} = ${value}"
                    }
                    env."AWS_DEFAULT_REGION"="${AWS_REGION}"
                    env."SESSION_NAME"="${customer}-${project}-${environment}"

                    sh "aws sts assume-role --role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/${customer}-${project}-${environment}-automation-platform --role-session-name '${SESSION_NAME}' --query 'Credentials' --output json > /tmp/creds-automation-platform.json"
                    def accessKey1 = sh(script: "jq -r '.AccessKeyId' /tmp/creds-automation-platform.json", returnStdout: true).trim()
                    def secretKey1 = sh(script: "jq -r '.SecretAccessKey' /tmp/creds-automation-platform.json", returnStdout: true).trim()
                    def sessionToken1 = sh(script: "jq -r '.SessionToken' /tmp/creds-automation-platform.json", returnStdout: true).trim()
                    env."AWS_ACCESS_KEY_ID" = accessKey1
                    env."AWS_SECRET_ACCESS_KEY" = secretKey1
                    env."AWS_SESSION_TOKEN" = sessionToken1
                    sh """#!/bin/bash
                        aws sts get-caller-identity
                    """
                    sh "rm -f /tmp/creds-automation-platform.json"
                    
                    sh "aws sts assume-role --role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/${customer}-${project}-${environment}-automation-platform-engplat --role-session-name '${SESSION_NAME}' --query 'Credentials' --output json > /tmp/creds-automation-platform-engplat.json"
                    def accessKey = sh(script: "jq -r '.AccessKeyId' /tmp/creds-automation-platform-engplat.json", returnStdout: true).trim()
                    def secretKey = sh(script: "jq -r '.SecretAccessKey' /tmp/creds-automation-platform-engplat.json", returnStdout: true).trim()
                    def sessionToken = sh(script: "jq -r '.SessionToken' /tmp/creds-automation-platform-engplat.json", returnStdout: true).trim()
                    env."AWS_ACCESS_KEY_ID" = accessKey
                    env."AWS_SECRET_ACCESS_KEY" = secretKey
                    env."AWS_SESSION_TOKEN" = sessionToken

                    sh """#!/bin/bash
                        aws sts get-caller-identity
                    """
                    sh "rm -f /tmp/creds-automation-platform-engplat.json"
                    
                    
                    def baseUrl = sh(script: "aws ssm get-parameter --name '${baseUrlParam}' --query 'Parameter.Value' --output text",returnStdout: true).trim()
                    def wssBaseUrl = sh(script: "echo '${baseUrl}' | sed 's|^https://|wss://|'", returnStdout: true).trim()
                    def publisherNamespace = sh(script: "aws ssm get-parameter --name '${publisherNamespaceParam}' --query 'Parameter.Value' --output text",returnStdout: true).trim()
                    def gameNamespace = params.GAME_NAMESPACE
                    def adminEmail = sh(script: "aws ssm get-parameter --name '${adminEmailParam}' --with-decryption --query 'Parameter.Value' --output text",returnStdout: true).trim()
                    def adminPassword = sh(script: "aws ssm get-parameter --name '${adminPasswordParam}' --with-decryption --query 'Parameter.Value' --output text",returnStdout: true).trim()
                    def clientId = sh(script: "aws ssm get-parameter --name '${clientIdParam}' --with-decryption --query 'Parameter.Value' --output text",returnStdout: true).trim()
                    def clientIdSecret = sh(script: "aws ssm get-parameter --name '${clientIdSecretParam}' --with-decryption --query 'Parameter.Value' --output text",returnStdout: true).trim()
                    def gameRecordKey = "sandGame"
                    def statCode = "lgtstatcode123"
                    def virtualCurrency = "LGTC"
                    def userLoginPassword = "3Z1FfOu5ntq0*g@D%ShwHt5d"

                    sh """#!/bin/bash
                        cd jenkins/jobs/scripts/load-generation-tool/
                        jq '.data[0].userLoginPassword = \"${userLoginPassword}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].baseURLDirect = \"${baseUrl}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].baseUrlWs = \"${wssBaseUrl}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].namespacePublisher = \"${publisherNamespace}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].namespaceGame = \"${gameNamespace}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].adminEmail = \"${adminEmail}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].adminPassword = \"${adminPassword}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].clientId = \"${clientId}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].clientSecret = \"${clientIdSecret}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].gameRecordKey = \"${gameRecordKey}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].statCode = \"${statCode}\"' config.json > config.tmp && mv config.tmp config.json
                        jq '.data[0].virtualCurrency = \"${virtualCurrency}\"' config.json > config.tmp && mv config.tmp config.json
                        
                    """
                }
            }

            stage('Run k6'){
                dir(workDir){
                    def runner
                    def testScenario = params.TEST_SCENARIO
                    def testServices = params.TEST_SERVICES
                    def testNumberOfUser = params.NUMBER_OF_USER
                    def testDuration = params.TEST_DURATION
                    def maxBackoffDuration = params.MAX_BACKOFF_DURATION
                    def prometheusServerUrl = params.PROMETHEUS_SERVER_URL

                    if (params.TEST_METHOD == "using-lobby") {
                        runner = "wsLoadGenerator.js"
                    } else {
                        runner = "loadGenerator.js"
                    }
                    def runnerCommandArgs = "PROMETHEUS_SERVER_URL=${prometheusServerUrl} ./k6_runner.sh linux-prometheus ${runner} ${testNumberOfUser} ${testDuration} ${testServices} ${testScenario} ${maxBackoffDuration}"

                    withCredentials([gitUsernamePassword(credentialsId: 'grafana_central_metricpublisher_apikey', gitToolName: 'Default')]) {
                        sh('chmod +x k6_runner.sh')
                        sh('PROMETHEUS_USERNAME=${GIT_USERNAME} PROMETHEUS_PASSWORD=${GIT_PASSWORD} ' + runnerCommandArgs)
                    }
                }
            }
        }
    }
} finally {
    node('load-generator-agent'){
        container('tool'){
            stage('send notif'){
                dir(tempDir){
                    sh 'echo "k6 send notif"'
                    withCredentials([string(credentialsId: "ab-automation-monitoring-slackbot-token", variable: 'slackToken')]) {
                        BUILD_TRIGGER_BY = currentBuild.getBuildCauses()[0].userName
                        BUILD_RESULT = currentBuild.currentResult
                        def command = """
                            curl --location 'https://slack.com/api/chat.postMessage' \
                                --header 'Content-Type: application/json' \
                                --header "Authorization: Bearer ${slackToken}" \
                                --data '{
                                    "channel": "C08FLU901ND",
                                    "username": "Hosting - Load Generation Tool - Bot",
                                    "blocks": [
                                        {
                                            "type": "header",
                                            "text": {
                                                "type": "plain_text",
                                                "text": ":chart_with_upwards_trend: Load generation process is complete :done-stamp: ",
                                                "emoji": true
                                            }
                                        },
                                        {
                                            "type": "section",
                                            "fields": [
                                                {
                                                    "type": "mrkdwn",
                                                    "text": "*Build Result:*\n${BUILD_RESULT}"
                                                }
                                            ]
                                        },
                                        {
                                            "type": "section",
                                            "text": {
                                                "type": "mrkdwn",
                                                "text": "*Triggerd by:*\n${BUILD_TRIGGER_BY}"
                                            }
                                        }
                                    ]
                                }'
                            """
                        sh command  
                    }
                }
            }
        }
    }
}

