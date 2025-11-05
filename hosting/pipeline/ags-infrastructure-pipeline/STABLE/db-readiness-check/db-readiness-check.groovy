import groovy.json.JsonOutput
import groovy.json.JsonSlurper

pipeline {
  agent {
    kubernetes {
      inheritFrom 'hosting-agent-steampipe'
      defaultContainer 'tool'
    }
  }
  parameters {
    string(name: 'CLUSTER_NAME', defaultValue: '', description: 'The environment name, e.g. abcdexample-justice-dev2')
  }

  stages {
    stage('Init') {
      steps {
        script {
          tempDir = "tmpdir-db-readiness-${BUILD_NUMBER}"

          userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']

          CUSTOMER_NAME = sh(returnStdout: true, script: """
              echo "${CLUSTER_NAME}" | awk -F'-' '{print \$1}'
          """
          ).trim()
          PROJECT_NAME = sh(returnStdout: true, script: """
              echo "${CLUSTER_NAME}" | awk -F'-' '{print \$2}'
          """
          ).trim()
          ENVIRONMENT_NAME = sh(returnStdout: true, script: """
              echo "${CLUSTER_NAME}" | awk -F'-' '{print \$3}'
          """
          ).trim()

          if (WORKSPACE.contains("DEVELOPMENT")) {
                PIPELINE_ENV = "DEVELOPMENT"
          } else {
                PIPELINE_ENV = "STABLE"
          }

        }
      }
    }

    stage('Init-Collect-Info') {
      steps {
        sshagent(['bitbucket-repo-read-only']) {
             withCredentials([
                string(credentialsId: "db-inventory-api-key", variable: 'dbInventoryAPIKey'),
                string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')
                ]) {
                dir(tempDir) {
                    sh """#!/bin/bash
                    export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

                    curl -s -XGET -H "accept: application/json" -H "x-api-key: ${dbInventoryAPIKey}" 'https://dbinventory-api.dev.hosting.accelbyte.io/listResourcesByType?ResourceType=EKS' > \$PWD/aws_config_output.json
                    """
                }
            }
        }
      }
    }

    stage('Init-EKS-Variables') {
      steps {
        script {
          dir(tempDir) {
            AWS_ACCOUNT_ID = sh(returnStdout: true, script: """
                cat aws_config_output.json | yq -r '.resources[] | select(.name == "${CLUSTER_NAME}").account' -ojson
            """
            ).trim()
            AWS_REGION = sh(returnStdout: true, script: """
                cat aws_config_output.json | yq -r '.resources[] | select(.name == "${CLUSTER_NAME}").region' -ojson
            """
            ).trim()
          }
        }
      }
    }

    stage('Init-Dependencies') {
      steps {
        dir(tempDir) {
        sh '''#!/bin/bash

        python3 -m venv $HOME/.venv
        source $HOME/.venv/bin/activate

        pushd ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script
        pip3 install -r requirements.txt
        popd
        '''
        }
      }
    }

    stage('Collect-AWS-Steampipe'){
      steps {
        withCredentials([
          file(credentialsId: "internal-infra-automation-gcp-serviceaccount", variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
          string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')
          ])
          {
            dir(tempDir){
              sh """#!/bin/bash
                set +x
                echo "Performing assume role as automation-platform..."
                export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
                    \$(aws sts assume-role \\
                    --role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/${CLUSTER_NAME}-automation-platform \\
                    --role-session-name ${CLUSTER_NAME} \\
                    --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
                    --output text))
                export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
                    \$(aws sts assume-role \\
                    --role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/${CLUSTER_NAME}-automation-platform-terraform \\
                    --role-session-name ${CLUSTER_NAME} \\
                    --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
                    --output text))

                if [[ ! -d result ]]; then
                  mkdir result
                fi

                export CUSTOMER_NAME=${CUSTOMER_NAME}              
                export PROJECT_NAME=${PROJECT_NAME}  
                export ENVIRONMENT_NAME=${ENVIRONMENT_NAME}
                export AWS_REGION=${AWS_REGION}
                export AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}

                source \$HOME/.venv/bin/activate

                echo "Updating kubeconfig ..."
                aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${AWS_REGION}

                echo "Obtaining data from AWS - Steampipe ..."
                bash ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/steampipe_get_data.sh

                echo "Obtaining data from AWS - CLI ..."
                bash ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/aws_cli_get_data.sh

                echo "Getting list of test cases ..."
                python3 ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/get_test_cases.py

                echo "Initiating kafka-exporter port-forward"
                bash ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/kafka_get_data.sh

              """
            }
          }
      }
    }

    stage('Run-Test'){
      steps {

        dir(tempDir){
          sh """#!/bin/bash
            set +x

            export CUSTOMER_NAME=${CUSTOMER_NAME}              
            export PROJECT_NAME=${PROJECT_NAME}  
            export ENVIRONMENT_NAME=${ENVIRONMENT_NAME}
            export AWS_REGION=${AWS_REGION}
            export AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}

            source \$HOME/.venv/bin/activate

            python3 ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/main.py
            python3 ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/main_single_table.py

          """
        }
      }
    }

    stage('Write-Results'){
      steps {
        withCredentials([
          file(credentialsId: "internal-infra-automation-gcp-serviceaccount", variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
          string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'SLACK_BOT_TOKEN')
          ])
          {
            dir(tempDir){
              sh """#!/bin/bash
                set +x

                export CUSTOMER_NAME=${CUSTOMER_NAME}              
                export PROJECT_NAME=${PROJECT_NAME}  
                export ENVIRONMENT_NAME=${ENVIRONMENT_NAME}
                export AWS_REGION=${AWS_REGION}
                export AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}
                export CLUSTER_NAME=${CLUSTER_NAME}

                export PIPELINE_ENV=${PIPELINE_ENV}
                export BUILD_URL=${BUILD_URL}
                export JOB_USER_ID="${userId}"

                source \$HOME/.venv/bin/activate

                python3 ../hosting/pipeline/ags-infrastructure-pipeline/DEVELOPMENT/db-readiness-check/script/sheet_writer.py
              """
            }
          }
      }
    }

  }
}




