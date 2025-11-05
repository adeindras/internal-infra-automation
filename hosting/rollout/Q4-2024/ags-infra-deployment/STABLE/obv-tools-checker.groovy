import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jenkins.plugins.http_request.ResponseContentSupplier

properties(
  [
    parameters([
	    string(description: "Environment to migrate", name: "targetEnvironmentName"),
      string(name: 'BUILD_TRIGGER_BY', description: 'Triggered By')
    ])
  ]
)

// constants
DEPLOYMENT_REPO_SLUG = "deployments"
String CUSTOMER
String ENVIRONMENT
String SERVICE_NAME
String JOB_INIT
def ENV_PATH = targetEnvironmentName.replace('-','/')
def latestMasterCommitHash = ""
def slackChannel
def slackMessage
def message
def statusMsg
def slackEmoji

def postData =  [
    channel: slackChannel,
    blocks: [
      [
        type: "section",
        text: [
          type: "mrkdwn",
          text: ":grafana: *OBSERVABILITY FUNCTIONALITY VALIDATION* :grafana:"
        ]
      ], 
      [
        type: "section",
        fields: [
          [
            type: "mrkdwn",
            text: "*Environment:*\n${targetEnvironmentName}"
          ],
          [
            type: "mrkdwn",
            text: "*Triggered by:*\n${BUILD_TRIGGER_BY}"
          ],
          [
            type: "mrkdwn",
            text: "*Pipeline:*\n<${env.BUILD_URL}|JENKINS>"
          ],
          [
            type: "mrkdwn",
            text: "*Status:*\nRunning :loading2:"
          ]
        ]
      ]
    ]
]

def generateMessage(postData, slackToken){
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
        def jsonSlurper = new JsonSlurper()
        def reply = post.getInputStream().getText()
        def replyMap = jsonSlurper.parseText(reply)
        return replyMap.message.ts
    }
}

def updateMessage(postData, slackMessage, slackToken){
  // POST
  def post = new URL("https://slack.com/api/chat.update").openConnection();
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
  }
}

def initMessage(message, slackChannel, slackMessage, slackToken, slackEmoji, statusMsg){
  // POST
  def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
  def postMessage =  [
    channel: slackChannel,
    blocks: [
      [
        type: "rich_text",
        elements: [
          [
            type: "rich_text_section",
            elements: [
              [
                type: "text",
                text: "${message} - ${statusMsg} "
              ],
              [
                type: "emoji",
                name: "${slackEmoji}"
              ]
            ]
          ]
        ]
      ]
    ],
    thread_ts: slackMessage
  ]
  def jsonPayload = JsonOutput.toJson(postMessage)
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
  }
}

node('init-deploy') {
    container('jnlp') {
        stage('Init') {
            createBanner("STAGE: Initializing.. sending status to bitbucket")
            echo "EKS Cluster Name: ${params.targetEnvironmentName}"

            // Extract CUSTOMER, SERVICE_NAME, and ENVIRONMENT from CLUSTER_NAME
            CUSTOMER = params.targetEnvironmentName.split('-')[0]
            SERVICE_NAME = params.targetEnvironmentName.split('-')[1]
            ENVIRONMENT = params.targetEnvironmentName.split('-')[2]
            
            // Display build name with the extracted values
            currentBuild.displayName = "#${BUILD_NUMBER}-obv-functionality-${params.targetEnvironmentName}"
            
        }
    }
}
node("deploy-agent") {
    container('tool') {
        try {
            stage('Checkout modified manifests') {
                createBanner("STAGE: Checkout SCM")
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        // Set slackChannel
                        if (WORKSPACE.contains('DEVELOPMENT')) {
                            slackChannel = 'C07UY55SE20'
                        } else {
                            slackChannel = 'C080SRE92NA'
                        }
                        
                        postData.channel = "${slackChannel}"
                        slackMessage = generateMessage(postData, slackToken)
                    }

                    withCredentials([string(credentialsId: "internal-deploy-tool-token-0", variable: 'bbAccessToken')]) {
                      def cmd = '''
                        # get latest commit from master
                        LATEST_MASTER_COMMIT_HASH="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/deployments/commits/master?pagelen=1" | jq -r '.values[0].hash')"
                        echo ${LATEST_MASTER_COMMIT_HASH}
                      '''
                      latestMasterCommitHash = sh(returnStdout: true, script: cmd).trim()
                    }
                    dir("${BUILD_NUMBER}") {
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
                              bitbucket-downloader -f $ENV_PATH/cluster-information.env \
                                  -r ${latestMasterCommitHash} \
                                  -s ${DEPLOYMENT_REPO_SLUG} \
                                  -o \$(pwd)
                          """
                      }
                    }
            }
            stage('Get cluster information') {
                createBanner("STAGE: Get cluster information")
                // get from file cluster-information.env in each cluster directory
                dir("${BUILD_NUMBER}/$ENV_PATH") {
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
                    echo ${env.targetEnvironmentName} ${env.AWS_REGION}
                    set -e
                    set -o pipefail
                    envsubst < ~/.aws/config.template > ~/.aws/config
                    # aws sts get-caller-identity
                    aws eks update-kubeconfig --name ${env.targetEnvironmentName} --region ${env.AWS_REGION}
                """
            }
            stage("Checking Otel") {
                createBanner("STAGE: Checking Otel Components")
                withCredentials([gitUsernamePassword(credentialsId: 'prometheus-api-central-stack', gitToolName: 'Default')]) {
                    // Checking Span Rate
                    message = "Checking Success Span Rate Receiver"
                    echo "${message}"

                    def checkerStatusSpan
                    def SpanQuery = 'query=(clamp_min(sum(rate(otelcol_receiver_accepted_spans_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver),1)/clamp_min((sum(rate(otelcol_receiver_refused_spans_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver))+(sum(rate(otelcol_receiver_accepted_spans_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver)),1)) * 100'
                    def FinalSpanQuery = SpanQuery.replace('targetEnvironmentName', "${env.targetEnvironmentName}")
                    def CurrentTs = sh(script: 'date +%s', returnStdout: true).trim().toLong()
                    def FivemAgoTs = CurrentTs-300
                    
                    def SuccessSpanRateAvg = sh(
                        script: """
                        curl -s -u ${GIT_USERNAME}:${GIT_PASSWORD} -G https://prometheus-prod-10-prod-us-central-0.grafana.net/api/prom/api/v1/query_range  --data-urlencode \'${FinalSpanQuery}\' --data-urlencode 'start=${FivemAgoTs}' --data-urlencode 'end=${CurrentTs}' --data-urlencode 'step=1m' | jq -r '[.data.result[].values | map(.[1] | tonumber)] | flatten | add / length'
                        """,
                        returnStdout: true).trim().toDouble()
                    echo "${SuccessSpanRateAvg}"

                    if (SuccessSpanRateAvg >= 94){
                        checkerStatusSpan = "OK"
                        statusMsg = "Done"
                        slackEmoji = "checkmark"
                    } else {
                        checkerStatusSpan = "NOT_OK"
                        statusMsg = "Failed"
                        slackEmoji = "x"
                        currentBuild.result = "UNSTABLE"
                    }
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        initMessage(message, slackChannel, slackMessage, slackToken, slackEmoji, statusMsg)
                    }

                    // Create table report
                    dir("${BUILD_NUMBER}/report") {
                      sh """
                        #!/bin/bash
                        echo "" >> diffTableReport.txt
                        {
                          printf ""
                          printf "+------------------------------------------------------------------------------+\n"
                          printf "| Service Name: %-62s |\n" "Otel-Span-Receiver"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          # Add each row with aligned columns
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Success Span Rate" "${SuccessSpanRateAvg}%" "94%" "${checkerStatusSpan}"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf ""
                        } >> diffTableReport.txt
                        echo "" >> diffTableReport.txt
                      """
                    }

                    // Checking Metrics Rate
                    message = "Checking Success Metrics Rate Receiver"
                    echo "${message}"

                    def checkerStatusMetrics
                    def MetricsQuery = 'query=(clamp_min(sum(rate(otelcol_receiver_accepted_metric_points_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver),1)/clamp_min((sum(rate(otelcol_receiver_refused_metric_points_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver))+(sum(rate(otelcol_receiver_accepted_metric_points_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver)),1)) * 100'
                    def FinalMetricsQuery = MetricsQuery.replace('targetEnvironmentName', "${env.targetEnvironmentName}")
                    
                    def SuccessMetricsRateAvg = sh(
                        script: """
                        curl -s -u ${GIT_USERNAME}:${GIT_PASSWORD} -G https://prometheus-prod-10-prod-us-central-0.grafana.net/api/prom/api/v1/query_range  --data-urlencode \'${FinalMetricsQuery}\' --data-urlencode 'start=${FivemAgoTs}' --data-urlencode 'end=${CurrentTs}' --data-urlencode 'step=1m' | jq -r '[.data.result[].values | map(.[1] | tonumber)] | flatten | add / length'
                        """,
                        returnStdout: true).trim().toDouble()
                    
                    echo "${SuccessMetricsRateAvg}"

                    if (SuccessMetricsRateAvg >= 94){
                        checkerStatusMetrics = "OK"
                        statusMsg = "Done"
                        slackEmoji = "checkmark"
                    } else {
                        checkerStatusMetrics = "NOT_OK"
                        statusMsg = "Failed"
                        slackEmoji = "x"
                        currentBuild.result = "UNSTABLE"
                    }
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        initMessage(message, slackChannel, slackMessage, slackToken, slackEmoji, statusMsg)
                    }

                    // Create table report
                    dir("${BUILD_NUMBER}/report") {
                      sh """
                        #!/bin/bash
                        echo "" >> diffTableReport.txt
                        {
                          printf ""
                          printf "+------------------------------------------------------------------------------+\n"
                          printf "| Service Name: %-62s |\n" "Otel-Metrics-Receiver"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          # Add each row with aligned columns
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Success Metrics Rate" "${SuccessMetricsRateAvg}%" "94%" "${checkerStatusMetrics}"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf ""
                        } >> diffTableReport.txt
                        echo "" >> diffTableReport.txt
                      """
                    }
                    
                    // Checking Logs Rate
                    message = "Checking Success Logs Rate Receiver"
                    echo "${message}"

                    def checkerStatusLogs
                    def LogsQuery = 'query=(clamp_min(sum(rate(otelcol_receiver_accepted_log_records_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver),1)/clamp_min((sum(rate(otelcol_receiver_refused_log_records_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver))+(sum(rate(otelcol_receiver_accepted_log_records_total{environment_name=\"targetEnvironmentName\", job="opentelemetry-collector"}[4m])) by (receiver)),1)) * 100'
                    def FinalLogsQuery = LogsQuery.replace('targetEnvironmentName', "${env.targetEnvironmentName}")
                    
                    def SuccessLogsRateAvg = sh(
                        script: """
                        curl -s -u ${GIT_USERNAME}:${GIT_PASSWORD} -G https://prometheus-prod-10-prod-us-central-0.grafana.net/api/prom/api/v1/query_range  --data-urlencode \'${FinalLogsQuery}\' --data-urlencode 'start=${FivemAgoTs}' --data-urlencode 'end=${CurrentTs}' --data-urlencode 'step=1m' | jq -r '[.data.result[].values | map(.[1] | tonumber)] | flatten | add / length'
                        """,
                        returnStdout: true).trim().toDouble()
                    
                    echo "${SuccessLogsRateAvg}"

                    if (SuccessLogsRateAvg >= 94){
                        checkerStatusLogs = "OK"
                        statusMsg = "Done"
                        slackEmoji = "checkmark"
                    } else {
                        checkerStatusLogs = "NOT_OK"
                        statusMsg = "Failed"
                        slackEmoji = "x"
                        currentBuild.result = "UNSTABLE"
                    }
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        initMessage(message, slackChannel, slackMessage, slackToken, slackEmoji, statusMsg)
                    }

                    // Create table report
                    dir("${BUILD_NUMBER}/report") {
                      sh """
                        #!/bin/bash
                        echo "" >> diffTableReport.txt
                        {
                          printf ""
                          printf "+------------------------------------------------------------------------------+\n"
                          printf "| Service Name: %-62s |\n" "Otel-Logs-Receiver"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          # Add each row with aligned columns
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Success Logs Rate" "${SuccessLogsRateAvg}%" "94%" "${checkerStatusLogs}"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf ""
                        } >> diffTableReport.txt
                        echo "" >> diffTableReport.txt
                      """
                    }
                }
            }  
            stage("Checking KubeStateMetrics") {
                createBanner("STAGE: Checking KubeStateMetrics Components")
                withCredentials([gitUsernamePassword(credentialsId: 'prometheus-api-central-stack', gitToolName: 'Default')]) {
                    // Check Count of KSM Metrics
                    message = "Checking Count of kube_state_.* metrics"
                    echo "${message}"

                    def checkerStatusKSM
                    def KSMQuery = 'query=count by (environment_name)({job=\"metric-services/infra\", environment_name=\"targetEnvironmentName\", __name__=~\"kube_.*\"})'
                    def FinalKSMQuery = KSMQuery.replace('targetEnvironmentName', "${env.targetEnvironmentName}")
                    def CurrentTs = sh(script: 'date +%s', returnStdout: true).trim().toLong()
                    def FivemAgoTs = CurrentTs-300
                    
                    def KSMCount = sh(
                        script: """
                        curl -s -u ${GIT_USERNAME}:${GIT_PASSWORD} -G https://prometheus-prod-10-prod-us-central-0.grafana.net/api/prom/api/v1/query_range  --data-urlencode \'${FinalKSMQuery}\' --data-urlencode 'start=${FivemAgoTs}' --data-urlencode 'end=${CurrentTs}' --data-urlencode 'step=1m' | jq -r '[.data.result[].values | map(.[1] | tonumber)] | flatten | add / length'
                        """,
                        returnStdout: true).trim().toDouble()
                    
                    echo "${KSMCount}"
                    

                    if (KSMCount >= 800){
                        checkerStatusKSM = "OK"
                        statusMsg = "Done"
                        slackEmoji = "checkmark"
                    } else {
                        checkerStatusKSM = "NOT_OK"
                        statusMsg = "Failed"
                        slackEmoji = "x"
                        currentBuild.result = "UNSTABLE"
                    }
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        initMessage(message, slackChannel, slackMessage, slackToken, slackEmoji, statusMsg)
                    }

                    // Create table report
                    dir("${BUILD_NUMBER}/report") {
                      sh """
                        #!/bin/bash
                        echo "" >> diffTableReport.txt
                        {
                          printf ""
                          printf "+------------------------------------------------------------------------------+\n"
                          printf "| Service Name: %-62s |\n" "Kube-State-Metrics"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          # Add each row with aligned columns
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "KSM Metrics Count" "${KSMCount}" "800" "${checkerStatusKSM}"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf ""
                        } >> diffTableReport.txt
                        echo "" >> diffTableReport.txt
                      """
                    }
                } 
            }
            stage("Checking Fluentd") {
                createBanner("STAGE: Checking Fluentd Components")
                withCredentials([gitUsernamePassword(credentialsId: 'prometheus-api-central-stack', gitToolName: 'Default')]) {
                    //Check Count of Exported Logs
                    message = "Checking Count of Exported Logs to S3 by Fluentd"
                    echo "${message}"

                    def checkerStatusFluentd
                    def CurrentTime = sh(script: 'date +%Y/%m/%d/%H', returnStdout: true).trim().toString()
                    echo "${CurrentTime}"
                    def LogsPath = "s3://${targetEnvironmentName}-logs/logs/access/${CurrentTime}"
                    echo "Checking Logs Count in ${LogsPath}"
                    def LogsCount = sh(
                        script: """
                            aws s3 ls "${LogsPath}" --recursive | wc -l
                            """,
                            returnStdout: true).trim().toLong()
                    echo "${LogsCount}"
                    
                    if (LogsCount >= 10){
                        checkerStatusFluentd = "OK"
                        statusMsg = "Done"
                        slackEmoji = "checkmark"
                    } else {
                        checkerStatusFluentd = "NOT_OK"
                        statusMsg = "Failed"
                        slackEmoji = "x"
                        currentBuild.result = "UNSTABLE"
                    }
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        initMessage(message, slackChannel, slackMessage, slackToken, slackEmoji, statusMsg)
                    }

                    // Create table report
                    dir("${BUILD_NUMBER}/report") {
                      sh """
                        #!/bin/bash
                        echo "" >> diffTableReport.txt
                        {
                          printf ""
                          printf "+------------------------------------------------------------------------------+\n"
                          printf "| Service Name: %-62s |\n" "Fluentd"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          # Add each row with aligned columns
                          printf "| %-28s | %-20s | %-13s | %-6s |\n" "Exported Logs to S3" "${LogsCount}" "10" "${checkerStatusFluentd}"
                          printf "+------------------------------+----------------------+---------------+--------+\n"
                          printf ""
                        } >> diffTableReport.txt
                        echo "" >> diffTableReport.txt
                      """
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
        } finally {
            stage('Results') {
                createBanner("STAGE: Result Checker")

                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    pushReport(slackChannel, slackMessage, slackToken)
                        echo "Update Slack Message"
                        postData.blocks[1].fields[3].text = postData.blocks[1].fields[3].text.replace("Running :loading2:", "Finished | <${BUILD_URL}Diff_20Report/|REPORT>")
                        postData.ts = "${slackMessage}"
                        updateMessage(postData, slackMessage, slackToken)
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

void createBanner(message) {
    ansiColor('xterm'){
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
        echo '\033[1;4;33m' + ":: ${message}"
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    }
}


def pushReport(slackChannel, slackMessage, slackToken){
  dir("${BUILD_NUMBER}") {
    publishHTML(target: [
      reportDir               : "report",
      reportFiles             : "diffTableReport.txt",
      reportName              : "Diff Report",
      alwaysLinkToLastBuild   : true,
      allowMissing            : true,
      keepAll                 : true 
    ])
  }

  // POST
  def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
  def postDataReport =  [
    channel: slackChannel,
    blocks: [
      [
        type: "rich_text",
        elements: [
          [
            type: "rich_text_section",
            elements: [
              [
                type: "link",
                url: "${BUILD_URL}Diff_20Report/",
                text: "--[REPORT]--",
                style: [
                  bold: true
                ]
              ]
            ]
          ]
        ]
      ]
    ],
    thread_ts: slackMessage
  ]
  def jsonPayload = JsonOutput.toJson(postDataReport)
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
  }
}