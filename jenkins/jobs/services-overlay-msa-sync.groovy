import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
  [
    parameters([
      string(defaultValue: '', name: 'targetCCU'),
      string(defaultValue: '', name: 'identifier'),
      string(defaultValue: '', name: 'targetEnvironmentName'),
      string(defaultValue: '', name: 'msaData'),
      string(defaultValue: '', name: 'slackThread')
    ])
  ]
)

// constants
TARGET_CCU = params.targetCCU
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName
def identifier = params.identifier
def msaData = JsonOutput.toJson(params.msaData)
def branchName = "autorightsizing-${TARGET_ENVIRONMENT_NAME}-CCU${TARGET_CCU}-${identifier}"


def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def slackThread = params.slackThread
def prHtmlLink

node('infra-sizing') {
  container('tool') {
    stage('Check Params'){
      if (TARGET_CCU == '') {
        currentBuild.result = 'NOT_BUILT'
        buildStopped = true
        error('Aborting the build')
      }
      if (TARGET_ENVIRONMENT_NAME == '') {
        currentBuild.result = 'NOT_BUILT'
        buildStopped = true
        error('Aborting the build')
      }
      if (msaData == '') {
        currentBuild.result = 'NOT_BUILT'
        buildStopped = true
        error('Aborting the build')
      }
      if (identifier == '') {
        currentBuild.result = 'NOT_BUILT'
        buildStopped = true
        error('Aborting the build')
      }
      echo TARGET_CCU
      echo TARGET_ENVIRONMENT_NAME
    }
    if (!buildStopped) {
        // currentBuild.displayName = "#${TARGET_CCU} - ${TARGET_ENVIRONMENT_NAME} - ${BUILD_NUMBER}"
      stage('Parse data and Modify env'){
        dir(tempDir) {
          def (customer, project, environment) = TARGET_ENVIRONMENT_NAME.split('-')
          sshagent(['bitbucket-repo-read-only']) {
            environmentDir = sh(
              returnStdout: true, 
              script: """
                echo ${TARGET_ENVIRONMENT_NAME} | sed 's/-/\\//g'
              """).trim()
            sh """#!/bin/bash 
              modifyServicesOverlay() {
                json=\${1}
                export name=\$(echo \${json} | jq -r '.name' | xargs)
                export memory_req=\$(echo \${json} | jq -r '.memory_req' | xargs)
                export memory_limit=\$(echo \${json} | jq -r '.memory_limit' | xargs)
                export cpu_req=\$(echo \${json} | jq -r '.cpu_req' | xargs)
                export cpu_limit=\$(echo \${json} | jq -r '.cpu_limit' | xargs)
                export mesh_cpu_req=\$(echo \${json} | jq -r '.mesh_cpu_req' | xargs)
                export mesh_cpu_limit=\$(echo \${json} | jq -r '.mesh_cpu_limit' | xargs)
                export mesh_memory_req=\$(echo \${json} | jq -r '.mesh_memory_req' | xargs)
                export mesh_memory_limit=\$(echo \${json} | jq -r '.mesh_memory_limit' | xargs)
                export hpa_min=\$(echo \${json} | jq -r '.hpa_min' | xargs)
                export hpa_max=\$(echo \${json} | jq -r '.hpa_max' | xargs)
                export hpa_cpu=\$(echo \${json} | jq -r '.hpa_cpu' | xargs)
                export hpa_memory=\$(echo \${json} | jq -r '.hpa_memory' | xargs)
                export svcName=\${name}
                echo "::: Modifying \${svcName}..."

                if [[ "\$#" -eq 2 ]]; then 
                  export serviceDir=\${2}
                else
                  export serviceDir=\${svcName}
                fi

                if [[ ! -d ./\${serviceDir} ]]; then 
                  echo ":---- Modifying \${svcName}... ERROR \${serviceDir} not found, skipping"
                  echo \${serviceDir} >> /tmp/notFoundServices${BUILD_NUMBER}.lst
                  if [[ \${svcName} == "analytics-game-telemetry-api" && \${serviceDir} == "analytics-game-telemetry" ]]; then
                    echo \${svcName} >> /tmp/notFoundServices${BUILD_NUMBER}.lst
                  fi
                  if [[ \${svcName} == "analytics-game-telemetry-worker" && \${serviceDir} == "analytics-game-telemetry" ]]; then
                    echo \${svcName} >> /tmp/notFoundServices${BUILD_NUMBER}.lst
                  fi
                  if [[ \${svcName} == "analytics-game-telemetry-monitoring" && \${serviceDir} == "analytics-game-telemetry" ]]; then
                    echo \${svcName} >> /tmp/notFoundServices${BUILD_NUMBER}.lst
                  fi
                  return 0
                fi

                for i in \$(find ./\${serviceDir} -type f ! -path '*/infrastructure/*'); do
                  # Manipulate memory req
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.spec.containers[0].resources.requests.memory = strenv(memory_req)' \${i}
                  # Manipulate memory limit
                  if [[ \${memory_limit} == "N" ]]; then 
                    export memory_limit=\${memory_req}
                  fi
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.spec.containers[0].resources.limits.memory = strenv(memory_limit)' \${i}

                  # Manipulate cpu req
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.spec.containers[0].resources.requests.cpu = strenv(cpu_req)' \${i}
                  # Manipulate cpu limit
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.spec.containers[0].resources.limits.cpu = null' \${i}


                  #yq e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)) as \$deployment | \$deployment.spec.template.spec.containers[0]' \${i}


                  # Manipulate hpa min
                  yq -i e '. | select(.kind == "HorizontalPodAutoscaler" and .metadata.name == strenv(svcName)).spec.minReplicas = env(hpa_min)' \${i}
                  # Manipulate hpa max
                  yq -i e '. | select(.kind == "HorizontalPodAutoscaler" and .metadata.name == strenv(svcName)).spec.maxReplicas = env(hpa_max)' \${i}
                  # Manipulate Utilizations
                  yq -i e '. | (select(.kind == "HorizontalPodAutoscaler" and .metadata.name == strenv(svcName)).spec.metrics[] | select(.resource.name == "cpu")).resource.target.averageUtilization = env(hpa_cpu)' \${i}
                  yq -i e '. | (select(.kind == "HorizontalPodAutoscaler" and .metadata.name == strenv(svcName)).spec.metrics[] | select(.resource.name == "memory")).resource.target.averageUtilization = env(hpa_memory)' \${i}

                  #yq e '. | select(.kind == "HorizontalPodAutoscaler" and .metadata.name == strenv(svcName)).spec' \${i}
                done

                # Linkerd QoS definitions
                for i in \$(find ./\${serviceDir} -type f -name '*.yaml' | grep infrastructure); do
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.metadata.annotations."config.linkerd.io/proxy-cpu-request" = strenv(mesh_cpu_req)' \${i}
                  if [[ \${mesh_cpu_limit} != *"N"* ]]; then 
                    yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.metadata.annotations."config.linkerd.io/proxy-cpu-limit" = strenv(mesh_cpu_limit)' \${i}
                  fi
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.metadata.annotations."config.linkerd.io/proxy-memory-request" = strenv(mesh_memory_req)' \${i}
                  yq -i e '. | select(.kind == "Deployment" and .metadata.name == strenv(svcName)).spec.template.metadata.annotations."config.linkerd.io/proxy-memory-limit" = strenv(mesh_memory_limit)' \${i}
                done

                
                if [[ \${svcName} == "analytics-game-telemetry-api" && \${serviceDir} == "analytics-game-telemetry" ]]; then
                  echo \${svcName} >> /tmp/processedServices${BUILD_NUMBER}.lst
                elif [[ \${svcName} == "analytics-game-telemetry-worker" && \${serviceDir} == "analytics-game-telemetry" ]]; then
                  echo \${svcName} >> /tmp/processedServices${BUILD_NUMBER}.lst
                elif [[ \${svcName} == "analytics-game-telemetry-monitoring" && \${serviceDir} == "analytics-game-telemetry" ]]; then
                  echo \${svcName} >> /tmp/processedServices${BUILD_NUMBER}.lst
                else
                  echo \${serviceDir} >> /tmp/processedServices${BUILD_NUMBER}.lst
                fi
                echo ":---- Modifying \${svcName}... OK"
              }

              commitAndPush() {
                git checkout -b ${branchName}
                git config --global user.email "build@accelbyte.net"
                git config --global user.name "Build AccelByte Autorightsizing"
                git add .
                git commit -m "feat: ${TARGET_ENVIRONMENT_NAME} Auto Rightsizing to ${TARGET_CCU} CCU. Committed by Jenkins"
                git push origin ${branchName}
              }

              export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
              git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/deployments.git"
              cd deployments/${environmentDir}
              if [[ \$(cat cluster-information.env | grep EKS_CLUSTER_NAME)  != *${TARGET_ENVIRONMENT_NAME}* ]]; then
                exit 1
              fi

              cd services-overlay
              echo ${msaData} | jq -cr '.services[]' | while read line; do
                name=\$(echo \${line} | jq -r '.name' | xargs)
                if [[ \${name} == "analytics-airflow-scheduler" ]] then
                  modifyServicesOverlay \${line} analytics-airflow
                elif [[ \${name} == "abversion-exporter" ]] then
                  echo "TODO:: handler for \${name} not found"
                elif [[ \${name} == "analytics-airflow-web" ]] then
                  modifyServicesOverlay \${line} analytics-airflow
                elif [[ \${name} == "analytics-game-telemetry-api" ]] then
                  modifyServicesOverlay \${line} analytics-game-telemetry
                elif [[ \${name} == "analytics-game-telemetry-monitoring" ]] then
                  modifyServicesOverlay \${line} analytics-game-telemetry
                elif [[ \${name} == "analytics-game-telemetry-worker" ]] then
                  modifyServicesOverlay \${line} analytics-game-telemetry
                elif [[ \${name} == "justice-dsm-controller-service" ]] then
                  modifyServicesOverlay \${line} justice-dedicated-server-manager-controller-service
                elif [[ \${name} == "justice-playerportal-website" ]] then
                  echo "TODO:: handler for \${name} not found"
                elif [[ \${name} == "opentelemetry-collector" ]] then
                  echo "TODO:: handler for \${name} not found"
                elif [[ \${name} == "platform-engineering-status-page-fe" ]] then
                  modifyServicesOverlay \${line} platform-engineering-status-page-service-frontend
                elif [[ \${name} == "" ]]; then
                  echo "blank"
                  continue
                else
                  modifyServicesOverlay \${line}
                fi
              done

              # Target ccu pod
              targetCCU=${TARGET_CCU} yq -i e '. | (select(.kind == "ConfigMap" and .metadata.name == "ab-infra-manager")) | .data.CCU = strenv(targetCCU)' ab-infra-manager/configmap.yaml
              
              commitAndPush
            """
          }

          def cmd = """
              ls deployments/$customer/$project/$environment/services-overlay | grep -v "analytics-game-telemetry\\|utils"
          """
          dirs = sh(returnStdout: true, script: cmd).trim()
          servicesOverlay = dirs.split('\n').collect{it.trim()}.findAll{it}
          
          cmd = """
              ls deployments/$customer/$project/$environment/services-overlay/analytics-game-telemetry | grep analytics-game-telemetry
          """
          dirs = sh(returnStdout: true, script: cmd).trim()
          analyticsGameTelemetry = dirs.split('\n').collect{it.trim()}.findAll{it}
          
          servicesOverlay.addAll(analyticsGameTelemetry)
          cmd = """
              cat /tmp/processedServices${BUILD_NUMBER}.lst
          """
          dirs = sh(returnStdout: true, script: cmd).trim()
          processedServices = dirs.split('\n').collect{it.trim()}.findAll{it}

          cmd = """
              cat /tmp/notFoundServices${BUILD_NUMBER}.lst || true
          """
          dirs = sh(returnStdout: true, script: cmd).trim()
          notFoundServices = dirs.split('\n').collect{it.trim()}.findAll{it}
          
          untouchedServices = servicesOverlay-processedServices
          untouchedServicesStr = untouchedServices.join("  \n")
          notFoundServicesStr = notFoundServices.join("  \n")

          totalServicesDeployments = servicesOverlay.size()
          totalProcessedServices = processedServices.size()
          totalUntouchedServices = untouchedServices.size()
        }
      }

      stage("Create PR") {
        prSummary="""
:: Total services in environment: \n $totalServicesDeployments \n \n
:: Total processed service: \n $totalProcessedServices \n \n
:: Total not processed service: \n $totalUntouchedServices \n \n
:: Not processed services (PLEASE CHECK IT MANUALLY): \n $untouchedServicesStr \n \n
        """
        withCredentials([string(credentialsId: "BitbucketAppKeyUserPassB64", variable: 'BitbucketAppKeyUserPassB64')]) {
          // POST
          def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests").openConnection();
          def postData =  [
            title: "feat: Auto Rightsizing ${TARGET_ENVIRONMENT_NAME} to ${TARGET_CCU} CCU",
            source: [
              branch: [
                name: "${branchName}"
              ]
            ],
            reviewers:[],
            destination: [
              branch: [
                name: "master"
              ]
            ],
            summary: [
              raw: "${prSummary}"
            ],
            close_source_branch: true
          ]
          def jsonPayload = JsonOutput.toJson(postData)
          post.setRequestMethod("POST")
          post.setDoOutput(true)
          post.setRequestProperty("Content-Type", "application/json")
          post.setRequestProperty("Authorization", "Basic ${BitbucketAppKeyUserPassB64}")
          post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
          def postRC = post.getResponseCode();
          println(postRC);
          if(postRC.equals(200) || postRC.equals(201)) {
            def jsonSlurper = new JsonSlurper()
            def reply = post.getInputStream().getText()
            def replyMap = jsonSlurper.parseText(reply)
            prHtmlLink = replyMap.links.html.href
            println(replyMap);
          }
        }
      }

      stage('Sending Slack Notification'){
        def elapsedTime = currentBuild.durationString.replaceAll(' and counting', "")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          // POST
          prSummary=""":: Total services in environment: \n $totalServicesDeployments \n \n
:: Total processed service: \n $totalProcessedServices \n \n
:: Total not processed service: \n $totalUntouchedServices \n \n
:: Not processed services (PLEASE CHECK IT MANUALLY): \n $untouchedServicesStr \n \n
          """
          def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
          def postData =  [
            channel: "C079A11910R",
            blocks: [
              [
                type: "section",
                text: [
                  type: "mrkdwn",
                  text: ":k8s: Sync service overlay with MSA data done :k8s:\nNOTE!!!\nThese services are not updated, please do it manually:\nopentelemetry-collector\njustice-playerportal-website\n"
                ]
              ],
              [
                type: "section",
                fields: [
                  [
                    type: "mrkdwn",
                    text: "*Jenkins:*\n<${BUILD_URL}/console|Go to Jenkins!>"
                  ],
                  [
                    type: "mrkdwn",
                    text: "*PR:*\n<${prHtmlLink}/console|Check PR!>"
                  ],
                  [
                    type: "mrkdwn",
                    text: "*Summary:*\n${prSummary}"
                  ],
                  [
                    type: "mrkdwn",
                    text: "*Execution Time:*\n${elapsedTime}"
                  ],
                ]
              ]
            ],
            thread_ts: "${slackThread}"
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
              println(post.getInputStream().getText())
          }
        }
      }
    }
  }
}