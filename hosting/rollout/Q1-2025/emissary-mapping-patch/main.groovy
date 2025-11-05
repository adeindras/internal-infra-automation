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

    stage('Run-Emissary-Mapping-Patch'){
      steps {

        dir(tempDir){
          sh """#!/bin/bash
            set +x

            export CUSTOMER_NAME=${CUSTOMER_NAME}              
            export PROJECT_NAME=${PROJECT_NAME}  
            export ENVIRONMENT_NAME=${ENVIRONMENT_NAME}
            export AWS_REGION=${AWS_REGION}
            export AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}

            if [[ ! -d ./deployments/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/services-overlay ]]; then
              echo "./deployments/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/services-overlay not found." 
              exit 1
            fi

            pushd ./deployments/ab-automation-scripts/emissary-mapping-patch > /dev/null
            ./main.sh
            popd > /dev/null

          """
        }

        script {
          CHANGES_DETECTED = sh(returnStdout: true, script: """#!/bin/bash
              set -e
              export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
              
              cd ./${tempDir}/deployments || exit 1

              get_num_of_changes=\$(git diff --name-only | wc -l || echo 0)

              if [[ \${get_num_of_changes} -ge 1 ]]; then
                  echo "true"
              else
                  echo "false"
              fi
          """
          ).trim()
        }

      }
    }

    stage('Push-Commit'){
      steps {
        sshagent(['bitbucket-repo-read-only']) {
          withCredentials([
            file(credentialsId: "internal-infra-automation-gcp-serviceaccount", variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
            string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'SLACK_BOT_TOKEN')])
            {
              script{
                if (CHANGES_DETECTED == 'true') {
                  dir(tempDir){
                        sh """#!/bin/bash
                        set -e
                        export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                        cd deployments

                        echo "Changes detected, creating branch ..."
                        git_branch_name="${BB_BRANCH_NAME}"
                        git checkout -b \${git_branch_name}
                        git config --global user.email "build@accelbyte.net"
                        git config --global user.name "Build AccelByte"
                        git add .
                        git commit -m "feat: emissary mapping patch - ${CLUSTER_NAME} - ${TIMESTAMP}"
                        git push --set-upstream origin \${git_branch_name}
                        """
                  }
                }
              }
            }
        }
      }
    }
  
    stage('Create-PR') {
      steps {
         withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
           script {
             if (CHANGES_DETECTED == 'true') {
              def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests").openConnection();
              def postData =  [
                title: "feat: emissary mapping patch ${CLUSTER_NAME}",
                source: [
                  branch: [
                    name: "${CLUSTER_NAME}-emissary-mapping-patch-${TIMESTAMP}"
                  ]
                ],
                reviewers:[],
                destination: [
                  branch: [
                    name: "master"
                  ]
                ],
                summary: [
                  raw: "${CLUSTER_NAME}",
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
              }
             }
          }
        }
      }
    }
  
  }
}
