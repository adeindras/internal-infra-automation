pipeline {
  triggers {
    GenericTrigger(
      causeString: 'A bitbucket event has been received',

      genericVariables: [
        [defaultValue: '', key: 'PR_TITLE', regexpFilter: '', value: '$.pullrequest.title'],
        [defaultValue: '', key: 'GIT_REF', regexpFilter: '', value: '$.pullrequest.source.commit.hash'],
        [defaultValue: '', key: 'MERGE_COMMIT', regexpFilter: '', value: '$.pullrequest.merge_commit.hash'],
        [defaultValue: '', key: 'COMMENT', regexpFilter: '', value: '$.comment.content.raw']
      ],

      genericHeaderVariables: [[key: 'x-event-key', regexpFilter: '']],

      regexpFilterExpression: '^(deploy: accelbyte-customerportal-stg [0-9]{10} pullrequest:fulfilled |deploy: accelbyte-(customerportal|docsportal)-dev [0-9]{10} pullrequest:fulfilled |deploy: accelbyte-(customerportal|docsportal)-prod [0-9]{10} (pullrequest:comment_created|pullrequest:created|pullrequest:fulfilled) (:plan)*)$',
      regexpFilterText: '$PR_TITLE $x_event_key $COMMENT',
      token: '',
      tokenCredentialId: 'customerportal-deploy-webhook-token'
    )
  }


  agent {
    kubernetes {
      yamlFile 'customer-portal/podTemplate.yaml'
    }
  }

  options {
    skipDefaultCheckout()
  }

  parameters {
    string(name: 'GIT_REF', defaultValue: 'master', description: 'Git ref of iac repo to be deployed')
    string(name: 'TOOL_GIT_BRANCH', defaultValue: 'master', description: 'Git ref of internal-infra-automation repo to be deployed')
    string(name: 'MERGE_COMMIT', defaultValue: '', description: '(Optional) Commit hash of the merge commit, if there is a merge event.')
    string(name: 'AWS_ACCOUNT_ID', defaultValue: '742281543583', description: 'Target AWS account id')
    string(name: 'REGION', defaultValue: 'us-east-2', description: 'Target AWS account id')
    string(name: 'CUSTOMER_NAME', defaultValue: 'customerportal', description: 'Target customer name')
    string(name: 'PROJECT_NAME', defaultValue: 'justice', description: 'Target project name')
    string(name: 'INFRA_AUTOMATION_TOOL_PATH', defaultValue: 'internal-infra-automation/customer-portal', description: 'Target tool path')
    string(name: 'x_event_key', defaultValue: '', description: 'Webhook event key. Leave this empty if the pipeline is triggered manually')
    string(name: 'PR_TITLE', defaultValue: '', description: 'Title of the pull request. Leave this empty if the pipeline is triggered manually')
    choice(name: 'ENV', choices: ['dev', 'prod', 'stg'], description: 'Target customer portal environment')
    choice(name: 'SERVICE', choices: ['autodetect', 'cp-audit-log', 'cp-login-app', 'cp-portal-app', 'cp-service-app', 'customer-database', 'justice-ic-service', 'metering-service', 'metering-worker', 'kafka-connect-sink-v2', 'forwarder', 'docs-api-explorer', 'docs-service'], description: '(Optional) Service to be deployed. If set to "autodetect", then only services changed by the commit will be deployed')
    choice(name: 'COMMAND', choices: ['apply --auto-approve', 'plan'], description: 'Command to be executed. Defaults to applying without asking for approval. If x_event_key is pullrequest:fulfilled, then this parameter is not used, and the changes will always be applied.')
    booleanParam(name: 'FORCE_DEPLOY', defaultValue: true, description: 'If true, a deployment will be started even if there is no change in the configuration')
  }

  environment {
    TFENV_AUTO_INSTALL = "false"
    TERRAGRUNT_PROVIDER_CACHE = "1"
    AWS_CONFIG_FILE = "$WORKSPACE/${INFRA_AUTOMATION_TOOL_PATH}/aws.config"
    TF_VAR_force_new_deployment = "${params.FORCE_DEPLOY}"
  }

  stages {
    stage('Read webhook event') {
      steps {
        script {
          environmentName = params.ENV

          if (PR_TITLE != "") {
            environmentName = PR_TITLE.find(~/deploy: accelbyte-(?:customerportal|docsportal)-(dev|stg|prod) [0-9]{10}/) { match ->
                return match[1]
            }
          }

          echo "${environmentName}"

          cpBasePath = "live/${params.AWS_ACCOUNT_ID}/${params.CUSTOMER_NAME}/${params.PROJECT_NAME}/${params.REGION}/${environmentName}/customer-portal/services"
          dpBasePath = "live/${params.AWS_ACCOUNT_ID}/${params.CUSTOMER_NAME}/${params.PROJECT_NAME}/${params.REGION}/${environmentName}/docs-portal/services"

          echo "${cpBasePath}"
          echo "${dpBasePath}"

          selectedCmd = params.COMMAND
          if (x_event_key != "") {
            if (x_event_key == 'pullrequest:fulfilled') {
              selectedCmd = 'apply --auto-approve'
            }

            if (x_event_key == 'pullrequest:comment_created') {
              if (COMMENT == ":plan") {
                selectedCmd = 'plan'
              }
            }
          }
        }
      }
    }

    stage('Preparation') {
      parallel {
        stage('Send start notification') {
          steps {
            script {
              slackResponse = sendJobStartNotification(environmentName)
            }
          }
        }

        stage('Install required terraform versions') {
           steps {
                sh "tfenv install 1.9.7"
                sh "tfenv install 1.3.10"
           }
        }

        stage('Checkout iac repository') {
          steps {
            script {
              gitRef = params.GIT_REF
              if (params.MERGE_COMMIT != "") {
                gitRef = params.MERGE_COMMIT
              }

              println(gitRef)

            }

            checkout (
              changelog: true,
              poll: false,
              scm: scmGit(
                branches: [[name: "${gitRef}"]],
                browser: bitbucket('https://bitbucket.org/accelbyte/iac'),
                extensions: [cloneOption(shallow: false), [$class: 'RelativeTargetDirectory', relativeTargetDir: 'iac']],
                userRemoteConfigs: [[credentialsId: 'Bitbucket_Build_EPP', url: 'https://engineering-platform-product@bitbucket.org/accelbyte/iac.git']]
              )
            )
            script {
              def shortSHA = sh(script: "git -C iac rev-parse --short=8 HEAD", returnStdout: true).trim()
              currentBuild.displayName =  "${environmentName} - ${params.COMMAND} - ${shortSHA}"
            }
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

            sh 'aws configure set web_identity_token_file $AWS_WEB_IDENTITY_TOKEN_FILE --profile default'
            sh 'aws configure set role_arn $AWS_ROLE_ARN --profile default'
          }
        }

      }
    }

    stage('Deploy changed services ') {
      when {
        expression { return params.SERVICE == 'autodetect' }
      }

      steps {
        script {
          parallel deployChangedServices("${selectedCmd}", "${gitRef}", params.CUSTOMER_NAME, params.PROJECT_NAME, environmentName, cpBasePath, dpBasePath )
        }
      }
    }

    stage("Deploy selected service") {
      when {
        expression { return params.SERVICE != 'autodetect' }
      }

      steps {
        script {
          currentBuild.description =  "(manual select) Env: ${environmentName} Service: ${params.SERVICE}. Force deployment: ${params.FORCE_DEPLOY}"
          changedServiceString = params.SERVICE
          slackResponse = updateDeployStartNotification(environmentName, gitRef, changedServiceString, slackResponse.channelId, slackResponse.ts)

          // Hacks to support docs-portal deployment
          if (params.SERVICE == "docs-api-explorer" || params.SERVICE == "docs-service") {
            dir("iac/${dpBasePath}/${params.SERVICE}") {
              runTerragrunt(selectedCmd, params.CUSTOMER_NAME, params.PROJECT_NAME, environmentName)
            }
          } else {
            dir("iac/${cpBasePath}/${params.SERVICE}") {
              runTerragrunt(selectedCmd, params.CUSTOMER_NAME, params.PROJECT_NAME, environmentName)
            }
          }
        }
      }
    }
  }

  post {
    always {
      script {
        slackResponse = updateDeployEndNotification(environmentName, gitRef, changedServiceString, slackResponse.channelId, slackResponse.ts)
      }
    }
  }
}

void runTerragrunt(String command, String customerName, String projectName, String environmentName) {
  // Unset default IRSA variables so the SDK chooses the correct profile.
  env.AWS_ROLE_ARN= ''
  env.AWS_WEB_IDENTITY_TOKEN_FILE= ''
  env.AWS_PROFILE="${customerName}-${projectName}-${environmentName}-automation-terraform"

  sh "terragrunt ${command}"
}

def deployChangedServices(String command, String ref, customerName, projectName, environmentName, cpBasePath, dpBasePath) {
  def shortSHA = sh(script: "git -C iac rev-parse --short=8 HEAD", returnStdout: true).trim()
  def changedServices = []

  currentBuild.displayName =  "${environmentName} - ${command} - ${shortSHA}"

  // Customer Portal
  sh "jq -n '.iac_repo_path=\"iac\" | .commit_hash=\"${ref}\" | .base_path=\"${cpBasePath}\"' > config.json"
  def changedCustomerPortalServices = sh(script: "python3 ${params.INFRA_AUTOMATION_TOOL_PATH }/deploy/get_changed_services.py", returnStdout: true).trim().tokenize('\n')
  currentBuild.description =  "(autodetect) Env: ${environmentName} Services: ${changedCustomerPortalServices.join(",")}. Force deployment: ${params.FORCE_DEPLOY}"
  changedCustomerPortalServiceString = changedCustomerPortalServices.join(",")

  def jobs = [:]
  for (service in changedCustomerPortalServices) {
    String service_name = service
    changedServices.add(service_name)
    echo "${service_name}"
    jobs[service_name] = {
      stage("Deploy ${service_name}") {
        dir("iac/${cpBasePath}/${service_name}") {
          runTerragrunt(command, customerName, projectName, environmentName)
        }
      }
    }
  }

  // Docs Portal
  sh "jq -n '.iac_repo_path=\"iac\" | .commit_hash=\"${ref}\" | .base_path=\"${dpBasePath}\"' > config.json"
  def changedDocsPortalServices = sh(script: "python3 ${params.INFRA_AUTOMATION_TOOL_PATH }/deploy/get_changed_services.py", returnStdout: true).trim().tokenize('\n')
  currentBuild.description =  "(autodetect) Env: ${environmentName} Services: ${changedDocsPortalServices.join(",")}. Force deployment: ${params.FORCE_DEPLOY}"

  for (service in changedDocsPortalServices) {
    String service_name = service
    changedServices.add(service_name)
    echo "${service_name}"
    jobs[service_name] = {
      stage("Deploy ${service_name}") {
        dir("iac/${dpBasePath}/${service_name}") {
          runTerragrunt(command, customerName, projectName, environmentName)
        }
      }
    }
  }

  changedServiceString = changedServices.join(",")
  slackResponse = updateDeployStartNotification(environmentName, ref, changedServiceString, slackResponse.channelId, slackResponse.ts)

  return jobs
}

def sendJobStartNotification(String environment) {
  def blocks = [
    [
     "type": "section",
       "text": [
         "type": "mrkdwn",
         "text": ":loading2: A new Customer Portal deployment has started:\n*<${currentBuild.absoluteUrl}|Link to Jenkins job>*"
       ]
     ]
  ]

  return slackSend(channel: "#ecs-customerportal-notif", blocks: blocks)
}

def updateDeployStartNotification(String environment, String ref, String services, String channelId, String ts) {
  def startTime = new Date(currentBuild.startTimeInMillis).toString()
  def blocks = [
    [
      "type": "section",
      "text": [
        "type": "mrkdwn",
        "text": ":loading2: A new Customer Portal deployment has started:\n*<${currentBuild.absoluteUrl}|Link to Jenkins job>*"
      ]
    ],
    [
      "type": "section",
      "fields": [
        [
          "type": "mrkdwn",
          "text": "*Environment:*\n ${environment}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Git ref:*\n <https://bitbucket.org/accelbyte/iac/commits/${ref}|${ref}>"
        ],
        [
          "type": "mrkdwn",
          "text": "*Services:*\n ${services}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Start time:*\n${startTime}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Status:*\nRunning"
        ],
        [
          "type": "mrkdwn",
          "text": "*Duration:*\n-"
        ]
      ]
    ]
  ]

  return slackSend(channel: channelId, blocks: blocks, timestamp: ts)
}


def updateDeployEndNotification(String environment, String ref, String services, String channelId, String ts) {
  def startTime = new Date(currentBuild.startTimeInMillis).toString()
  def statusEmoji = ":white_check_mark:"
  if (currentBuild.result != "SUCCESS") {
    statusEmoji = ":x:"
  }

  def blocks = [
    [
      "type": "section",
      "text": [
        "type": "mrkdwn",
        "text": "${statusEmoji} The following customer Portal deployment has finished:\n*<${currentBuild.absoluteUrl}|Link to Jenkins job>*"
      ]
    ],
    [
      "type": "section",
      "fields": [
        [
          "type": "mrkdwn",
          "text": "*Environment:*\n ${environment}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Git ref:*\n <https://bitbucket.org/accelbyte/iac/commits/${ref}|${ref}>"
        ],
        [
          "type": "mrkdwn",
          "text": "*Services:*\n ${services}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Start time:*\n${startTime}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Status:*\n ${currentBuild.result}"
        ],
        [
          "type": "mrkdwn",
          "text": "*Duration:*\n${currentBuild.duration / 1000} seconds"
        ]
      ]
    ]
  ]

  return slackSend(channel: channelId, blocks: blocks, timestamp: ts)
}
