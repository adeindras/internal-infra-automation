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
          tempDir = "tmpdir-emissary-mapping-patch-${BUILD_NUMBER}"

          deploymentsTemp = "${tempDir}/deploymentsTemp"

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

          TIMESTAMP = sh(returnStdout: true, script: """
              date +%s
          """
          ).trim()

          BB_BRANCH_NAME = sh(returnStdout: true, script: """
            echo "${CLUSTER_NAME}-emissary-mapping-patch-${TIMESTAMP}"
          """).trim()

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

    stage('Init-Clone-Deployments') {
      steps {
        dir(tempDir) {
          sshagent(['bitbucket-repo-read-only']) {
              sh """#!/bin/bash
                set -e

                export CLUSTER_NAME="${CLUSTER_NAME}"

                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                git clone --quiet "git@bitbucket.org:accelbyte/deployments.git" --depth 1 || true

                if [[ -d ./deployments ]]; then
                  pushd ./deployments > /dev/null
                  popd > /dev/null
                else
                  echo "deployments repo directory not found. Aborted."
                  exit 1
                fi
              """
          }
        }
      }
    }

    stage('Run-Validate'){
      steps {
        dir(tempDir){
          sh """#!/bin/bash
            set +x

            export CUSTOMER_NAME=${CUSTOMER_NAME}              
            export PROJECT_NAME=${PROJECT_NAME}  
            export ENVIRONMENT_NAME=${ENVIRONMENT_NAME}
            export AWS_REGION=${AWS_REGION}
            export AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}

            echo "[+] Performing assume role as automation-platform..."
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

            echo "[+] Updating kubeconfig for ${CLUSTER_NAME}"
            aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${AWS_REGION}

            pushd ./deployments/ab-automation-scripts/emissary-mapping-patch
            ./validate.sh ${CLUSTER_NAME}
            popd

          """

        }
      }
    }
  
  }
}
