import groovy.json.JsonOutput
import groovy.json.JsonSlurper

pipeline {
  agent {
    kubernetes {
      yamlFile 'customer-portal/podTemplate.yaml'
    }
  }

  triggers {
    parameterizedCron('''
      H/30 0-13 * * 1-5 %ENV=dev
      H/30 0-13 * * 1-5 %ENV=stg
    ''')
  }

  options {
    skipDefaultCheckout()
  }

  parameters {
    string(name: 'TOOL_GIT_BRANCH', defaultValue: 'master', description: 'Git ref of internal-infra-automation repo to be deployed')
    string(name: 'GIT_BRANCH', defaultValue: 'master', description: 'Git ref of iac repo to be deployed')
    string(name: 'INFRA_AUTOMATION_TOOL_PATH', defaultValue: 'internal-infra-automation/customer-portal', description: 'Target tool path')
    choice(name: 'ENV', choices: ['dev', 'stg', 'prod'], description: 'Target customer portal environment')
    booleanParam(name: 'AUTO_MERGE', defaultValue: true, description: 'If true, the pull request will be automatically merged.')
  }

  environment {
    AWS_CONFIG_FILE = "$WORKSPACE/${params.INFRA_AUTOMATION_TOOL_PATH}/aws.config"
  }

  stages {
    stage('Preparation') {
      parallel {
        stage('Checkout iac repository') {
          steps {
            checkout (
              changelog: true,
              poll: false,
              scm: scmGit(
                branches: [[name: "${params.GIT_BRANCH}"]],
                browser: bitbucket('https://bitbucket.org/accelbyte/iac'),
                extensions: [cloneOption(shallow: false), [$class: 'RelativeTargetDirectory', relativeTargetDir: 'iac']],
                userRemoteConfigs: [[credentialsId: 'Bitbucket_Build_EPP', url: 'https://engineering-platform-product@bitbucket.org/accelbyte/iac.git']]
              )
            )
          }
        }

        stage('Load infra-automation configuration') {
          steps {
            checkout (
              changelog: true,
              poll: false,
              scm: scmGit(
                branches: [[name: "${params.TOOL_GIT_BRANCH}"]],
                browser: bitbucket('https://bitbucket.org/accelbyte/internal-infra-automation'),
                extensions: [cloneOption(shallow: true), [$class: 'RelativeTargetDirectory', relativeTargetDir: 'internal-infra-automation']],
                userRemoteConfigs: [[credentialsId: 'Bitbucket_Build_EPP', url: 'https://engineering-platform-product@bitbucket.org/accelbyte/internal-infra-automation.git']]
              )
            )

            sh '''
              aws configure set web_identity_token_file $AWS_WEB_IDENTITY_TOKEN_FILE --profile default
              aws configure set role_arn $AWS_ROLE_ARN --profile default
            '''
          }
        }

      }
    }

    stage('Update image tags files') {
      environment {
        AWS_PROFILE = "accelbyte-justice-demo-automation-terraform"
      }

      steps {
         sshagent(['bitbucket-repo-read-only']) {
           script {
             timestamp = sh(script: "date +%s", returnStdout: true).trim()
           }

           sh """#!/usr/bin/env bash
             set -euo pipefail

             set +x
             python3 -m venv venv
             . venv/bin/activate > /dev/null
             pip install boto3 > /dev/null
             if [ '${params.ENV}' = 'stg' ]; then
              python3 '${params.INFRA_AUTOMATION_TOOL_PATH}/update_image/get_latest_ecr_image.py' '${params.INFRA_AUTOMATION_TOOL_PATH}/update_image/customerportalservices.json' 'iac/live/742281543583/customerportal/justice/us-east-2/${params.ENV}/customer-portal/services'
             else
              python3 '${params.INFRA_AUTOMATION_TOOL_PATH}/update_image/get_latest_ecr_image.py' '${params.INFRA_AUTOMATION_TOOL_PATH}/update_image/customerportalservices.json' 'iac/live/742281543583/customerportal/justice/us-east-2/${params.ENV}/customer-portal/services'
              python3 '${params.INFRA_AUTOMATION_TOOL_PATH}/update_image/get_latest_ecr_image.py' '${params.INFRA_AUTOMATION_TOOL_PATH}/update_image/docsportalservices.json' 'iac/live/742281543583/customerportal/justice/us-east-2/${params.ENV}/docs-portal/services'
             fi
             set -x

             cd ./iac
             CHANGES=\"\$(git status --porcelain)\"

             if [[ \"\${CHANGES}\" != "" ]]; then
               mkdir -p ~/.ssh && curl https://bitbucket.org/site/ssh >> ~/.ssh/known_hosts
               git remote add originssh git@bitbucket.org:accelbyte/iac.git
               git checkout -b 'customerportal-${params.ENV}-deploy-${timestamp}'
               git config user.email 'build@accelbyte.net'
               git config user.name 'Build AccelByte'
               git config push.default current
               git commit -am 'customer_portal_image_automation: ${currentBuild.number}'
               git push originssh
             fi
           """
         }
      }
    }

    stage('Create a pull request') {
      steps {
         withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
           script {
             def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
             def prSummary = """
                This pull request contains automated changes to update container images of accelbyte-customerportal-${params.ENV}.
                Please update the pull request as needed, then merge the pull request to continue with the deployment.
             """
             def postData =  [
               title: "deploy: accelbyte-customerportal-${params.ENV} ${timestamp}",
               source: [
                 branch: [
                   name: "customerportal-${params.ENV}-deploy-${timestamp}"
                 ]
               ],
               reviewers: [
                   [
                       uuid: "{6cee0dcd-3d6e-4ef1-8cd0-4ca217ed32d2}" // Adin Baskoro Pratomo
                   ]
               ],
               destination: [
                 branch: [
                   name: "${params.GIT_BRANCH}"
                 ]
               ],
               summary: [
                 raw: "${prSummary}",
                 // markup: "markdown"
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

             if(postRC.equals(200) || postRC.equals(201)) {
               def jsonSlurper = new JsonSlurper()
               def response = post.getInputStream().getText()
               def responseMap = jsonSlurper.parseText(response)
               prHtmlLink = responseMap.links.html.href
               println(prHtmlLink)

               prId = responseMap.id.toString()

               if (params.AUTO_MERGE) {
                 def mergePRPost = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests/${prId}/merge").openConnection();
                 def mergePostBody = [
                  type: "pullrequest",
                  message: "Automatically merged: ${currentBuild.absoluteUrl}",
                  close_source_branch: true,
                  merge_strategy: "squash"
                 ]
                 def mergeJSONPayload = JsonOutput.toJson(mergePostBody)
                 mergePRPost.setDoOutput(true)
                 mergePRPost.setRequestProperty("Content-Type", "application/json")
                 mergePRPost.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
                 mergePRPost.getOutputStream().write(mergeJSONPayload.getBytes("UTF-8"));
                 def mergePRPostRC = mergePRPost.getResponseCode();
                 println(mergePRPostRC.toString())
               }

               sendPRSlackNotification(params.ENV, prHtmlLink)
             }
          }
				}
      }
    }
  }
}

def sendPRSlackNotification(String environment, String prLink) {
  def actionMsg = "created"
  if (params.AUTO_MERGE) {
    actionMsg = actionMsg + " and merged"
  }

	def blocks = [
		[
			"type": "section",
			"text": [
				"type": "mrkdwn",
				"text": "A new automated PR has been ${actionMsg} for accelbyte-customerportal-${environment}: \n*<${currentBuild.absoluteUrl}|Link to Jenkins job>*\n*<${prLink}|Link to BitBucket PR>*"
			]
		]
	]

  slackResponse = slackSend(channel: "#ecs-customerportal-notif", blocks: blocks)
}
