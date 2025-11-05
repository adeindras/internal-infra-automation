import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String[] modifiedDirs
def awsAccountID
def clientName
def projectName
def envName
def clusterName
def autorightsizingPrIdentifier = "autorightsizing"
def jenkinsPrIdentifier = "jenkins"
def identifier
def buildStopped = false
def msaDoc = "https://docs.google.com/spreadsheets/d/17tls6xa6U6eAvfK6ST6--b5iUt6YexAuLLjH_6VsavU/edit?usp=sharing"
def slackThread
def autorightsizingModeEnabled = "false"

node('infra-sizing') {
  container('tool') {
    stage('Check Params'){
      if (WORKSPACE.contains("DEVELOPMENT")) {
        autorightsizingPrIdentifier = "DEVELOPMENT" + autorightsizingPrIdentifier
        jenkinsPrIdentifier = "DEVELOPMENT" + jenkinsPrIdentifier
      }

      identifier = sh(returnStdout: true, script: '''
          echo "${branchName}" | awk -F'-' '{print $1}'
        '''
      ).trim()

      if ((identifier != autorightsizingPrIdentifier) && (identifier != jenkinsPrIdentifier) ) {
        currentBuild.result = 'ABORTED'
        buildStopped = true
        currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
        error('Not autorightsizing changes - Aborting the build.')
      }

      if (identifier == autorightsizingPrIdentifier) {
        autorightsizingModeEnabled = "true"
      }

      if (mergeDestinationBranch != "master") {
        currentBuild.result = 'ABORTED'
        buildStopped = true
        currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
        error('Not master merge changes - Aborting the build.')
      }

      awsAccountID = sh(returnStdout: true, script: '''
          echo "${branchName}" | awk -F'-' '{print $2}'
        '''
      ).trim()

      clientName = sh(returnStdout: true, script: '''
          echo "${branchName}" | awk -F'-' '{print $3}'
        '''
      ).trim()

      projectName = sh(returnStdout: true, script: '''
          echo "${branchName}" | awk -F'-' '{print $4}'
        '''
      ).trim()

      envName = sh(returnStdout: true, script: '''
          echo "${branchName}" | awk -F'-' '{print $5}'
        '''
      ).trim()

      clusterName = clientName + "-" + projectName + "-" + envName
      echo identifier
      echo awsAccountID
      echo clusterName
      echo actor
      echo branchName
      echo "autorightsizingModeEnabled: ${autorightsizingModeEnabled}"
    }
    if (!buildStopped) {
      currentBuild.displayName = "#${branchName} - ${BUILD_NUMBER}"

      if (!autorightsizingModeEnabled) {
        currentBuild.displayName = "jankins - ${currentBuild.displayName}"
      }

      stage('Get Modified Folder'){
        withCredentials([string(credentialsId: "BitbucketIacAccessTokenRW", variable: 'bbAccessToken')]) {
          def cmd = '''
            if [[ "${prState}" == "MERGED" ]]; then
              # changes traversal
              DIFF="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/diff/${mergeCommitHash}" |
                  grep -E '^\\+\\+\\+ b/' |
                  cut -d ' ' -f2- |
                  cut -c3- |
                  grep terragrunt.hcl |
                  sed 's/terragrunt\\.hcl//g' |
                  sort -u)"
            else
              # get latest commit from master
              latestMasterCommitHash="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/commits/master?pagelen=1" | jq -r '.values[0].hash')"

              # changes traversal
              DIFF="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/diff/${changesCommitHash}..${latestMasterCommitHash}" |
                  grep -E '^\\+\\+\\+ b/' |
                  cut -d ' ' -f2- |
                  cut -c3- |
                  grep terragrunt.hcl |
                  sed 's/terragrunt\\.hcl//g' |
                  sort -u)"
            fi
            echo ${DIFF}
          '''
          diff = sh(returnStdout: true, script: cmd).trim()
          modifiedDirs = diff.split(' ').collect{it.trim()}.findAll{it}
        }
      }
      if (prState == 'MERGED'){
        if (autorightsizingModeEnabled == "true") {
          stage('Send #report-infra-changes'){
            withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
              def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
              def postData =  [
                channel: "C017L2M1C3D",
                blocks: [
                  [
                    type: "section",
                    text: [
                      type: "mrkdwn",
                      text: ":robot_face: [${clientName}][${envName}] Auto rightsizing :robot_face:"
                    ]
                  ],
                  [
                    type: "section",
                    fields: [
                      [
                        type: "mrkdwn",
                        text: "*Desc:*\nThis action will be executed in Jenkins runner, please check the commit to see the changes and progresses"
                      ],
                      [
                        type: "mrkdwn",
                        text: "*Actor:*\n${actor}"
                      ],
                      [
                        type: "mrkdwn",
                        text: "*Commit:*\n<https://bitbucket.org/accelbyte/iac/commits/${mergeCommitHash}|${mergeCommitHash}>"
                      ],
                      [
                        type: "mrkdwn",
                        text: "*AWS Account:*\n${awsAccountID}"
                      ],
                      [
                        type: "mrkdwn",
                        text: "*Impact:*\nDB Failover, service reconnecting to the new data store endpoints"
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
      stage('Dispatch'){
        modifiedDirs.each { dir ->
          try {
            if (dir.contains(clientName) && dir.contains(projectName) && dir.contains(envName)) {
              echo "Triggering rightsizing job for modified folder: $dir"
              build job: 'Resources-Terragrunt-Executor',
              parameters: [
                [$class: 'StringParameterValue', name: 'awsAccountID', value: awsAccountID],
                [$class: 'StringParameterValue', name: 'directory', value: dir],
                [$class: 'StringParameterValue', name: 'clusterName', value: clusterName],
                [$class: 'StringParameterValue', name: 'actor', value: actor],
                [$class: 'StringParameterValue', name: 'branchName', value: prState == 'MERGED' ? 'master' : branchName],
                [$class: 'StringParameterValue', name: 'action', value: prState == 'MERGED' ? 'apply --auto-approve' : 'plan'],
                [$class: 'StringParameterValue', name: 'commitHash', value: prState == 'MERGED' ? mergeCommitHash : changesCommitHash],
                [$class: 'StringParameterValue', name: 'slackThread', value: slackThread],
                [$class: 'StringParameterValue', name: 'autorightsizingModeEnabled', value: autorightsizingModeEnabled],
              ],
              wait: false
            } else {
              echo "Modified cluster name doesn't match with the specified cluster name in branch"
              echo "Skipping changes from $dir"
            }
          } catch (Exception e) {
            echo "Caught an exception: ${e.message}"
            throw e
          }
        }
      }
    }
    
  }
}
