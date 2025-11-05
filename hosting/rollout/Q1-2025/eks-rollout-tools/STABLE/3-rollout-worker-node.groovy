import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
  [
    parameters([
      choice(choices: envList, description: "Environment to rollout", name: "targetEnvironmentName"),
      choice(choices: ["critical-on-init", "worker"], description: 'critical-on-init for karpenter env and worker for non-karpenter env', name: "asgTagName"),
      booleanParam(defaultValue: true, name: 'cordonNodes', description: 'Cordon existing nodes that not match with control plane version'),
      booleanParam(defaultValue: false, name: 'replaceNodes', description: 'Replace existing nodes (karpenter ASG critical-on-init, non-karpenter ASG worker).'),
      booleanParam(defaultValue: false, name: 'skipRolloutSts', description: 'Rollout Restart Statefulset i.e postgresql, redis, opensearch'),
      booleanParam(defaultValue: false, name: 'skipRolloutEmissary', description: 'Rollout Restart emissary-ingress, emissary-ingress-websocket'),
      booleanParam(defaultValue: false, name: 'skipRolloutJusticeDeployment', description: 'Rollout All Justice Deployment'),
      booleanParam(defaultValue: false, name: 'skipRolloutExtendDeployment', description: 'Checklist to skip restart on Extend services'),
      booleanParam(defaultValue: false, name: 'skipRolloutRemainingDeployment', description: 'Rollout Deployment in the namespace (i.e tools, wiz-integration, kube-system, logging, etc)'),
      booleanParam(defaultValue: false, name: 'skipRolloutArmadaServices', description: 'Checklist to skip restart Armada services'),
      string(defaultValue: '', name: 'slackThreadUrl', description: 'Send the notification to specific Slack Thread (i.e https://accelbyte.slack.com/archives/C080SRE92NA/p1736426045378049)')
    ])
  ]
)

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
        envData.push("Error getting environment list data")
    } else {
        return envData
    }
}

def getSpecificAsg(targetEnvironmentName, awsRegion, tagName) {
    return sh(returnStdout: true, script: """
      aws autoscaling describe-auto-scaling-groups \
      --region "${awsRegion}" \
      --query "AutoScalingGroups[*]" \
      --output yaml \
      --no-cli-pager | yq -r '
      .[] | select(
          .AutoScalingGroupName | contains("${tagName}") and 
          .AutoScalingGroupName | contains("${targetEnvironmentName}") and 
          .Instances | length >= 2
      ) | { 
          "asgName": .AutoScalingGroupName, 
          "asgDesiredCapacity": .DesiredCapacity, 
          "asgMaxCapacity": .MaxSize, 
          "asgInstances": [.Instances[].InstanceId] 
      }'
    """).trim()
}

def getLastStatusAsg(asgName, awsRegion) {
  return sh(returnStdout: true, script: """
    set +x
    aws autoscaling describe-scaling-activities \
    --auto-scaling-group-name ${asgName} \
    --region ${awsRegion} \
    --output yaml \
    --no-cli-pager | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
  """).trim()
}

def setAsgMaxCapacity(asgName, awsRegion, maxCapacity) {
  sh """
    set +x
    aws autoscaling update-auto-scaling-group \
      --auto-scaling-group-name ${asgName} \
      --max-size ${maxCapacity} \
      --region ${awsRegion}
  """
}

def scaleAsg(asgName, awsRegion, desiredCapacity) {
  return sh(returnStdout: true, script: """
    set +x
    aws autoscaling set-desired-capacity \
    --auto-scaling-group-name ${asgName} \
    --desired-capacity ${desiredCapacity} \
    --region ${awsRegion} || echo "ScalingActivityInProgress"
  """).trim()
}

def configureProtectedInstancesInAsg(asgName, awsRegion, params) {
  // --no-new-instances-protected-from-scale-in
  sh """
  aws autoscaling update-auto-scaling-group \
    --auto-scaling-group-name ${asgName} \
    --region ${awsRegion}	\
    ${params}
  """
}

def getStatefulSets(withPVC) {
    def pvcCondition = withPVC ? "!= null" : "== null"

    def query = """
        .items[] | select(
            .spec.volumeClaimTemplates[].metadata.name ${pvcCondition} and 
            .metadata.namespace != "justice" and
            .metadata.name | contains("standalone") | not and
            .metadata.namespace != "justice-play" and
            .spec.replicas != 0 and
            .status.availableReplicas != 0 and
            .metadata.name | contains("prometheus") | not
        ) | .metadata.namespace + "---" + .metadata.name + "---" + .status.replicas
    """.trim()

    return sh(returnStdout: true, script: """
        set +x
        kubectl get sts -A -o yaml | yq '${query}'
    """).trim().split("\n").findAll { it }
}

def scaleStatefulSetIndividually(stsList, slackThread, slackChannel) {
    stsList.each { line ->
        def (namespace, stsName, replicas) = line.split('---')

        if (namespace == "redis") {
          def responseResultRedisStatus = checkRedisStatus()
          def responseResultRedisVersion = checkRedisVersion("17")

          if (responseResultRedisVersion == true && responseResultRedisStatus == true) {
            dumpDataRedis()
          } else if (responseResultRedisVersion == true && responseResultRedisStatus == false) {
            def userInput = input(
              id: 'userInput', 
              message: 'Redis 17 is not running. Please dump redis data manually and ensure redis is running before continue, otherwise confirm', 
              parameters: [
                [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
              ]
            )

            if(!userInput) {
              echo "Skip dump data redis manually"
            }
          } else if (responseResultRedisVersion == false && responseResultRedisStatus == true) {
            echo "Redis version is not 17 (AOF Enabled) and running"
          }
        }

        echo "Scaling down StatefulSet ${stsName} in namespace ${namespace} to 0..."
        sh "kubectl -n ${namespace} scale sts ${stsName} --replicas=0"

        while (true) {
            stsInfoJson = sh( returnStdout: true, script: """
                set +x
                kubectl -n ${namespace} get sts ${stsName} -oyaml | yq -o=json '.status | {"availableReplicas": .availableReplicas, "statusReplicas": .replicas}'
            """).trim()
            
            def stsInfo = readJSON text: stsInfoJson
            def availableReplicas = stsInfo.availableReplicas ?: 0
            def statusReplicas = stsInfo.statusReplicas ?: 0

            volumeName = sh(returnStdout: true, script: """
                set +x
                kubectl get pvc -A -oyaml | yq '.items[] | select(.metadata.name | contains("${stsName}-0")) | .spec.volumeName'
            """).trim()

            statusAttachment = sh(returnStdout: true, script: """
                set +x
                kubectl get volumeattachments -oyaml | yq '.items[] | select(.spec.source.persistentVolumeName=="${volumeName}") | .status.attached // false'
            """).trim()
          
            if (availableReplicas == 0 && statusReplicas == 0 && statusAttachment == "false") {
                echo "StatefulSet ${stsName} in namespace ${namespace} has scaled to 0."
                break
            }
            else {
                echo "StatefulSet ${stsName} in namespace ${namespace} is still scaling down. Waiting for 5 seconds..."
                sleep(5)
            }
        }

        echo "Scaling up StatefulSet ${stsName} in namespace ${namespace} to ${replicas} replicas..."
        sh "kubectl -n ${namespace} scale sts ${stsName} --replicas=${replicas}"

        sleep(5) // Wait until the pod scheduled

        def startTime = System.currentTimeMillis()
        def maxWaitMillis = 5 * 60 * 1000 // 5 minutes in milliseconds
        def reminderSent = false

        while (true) {
            podInfoJson = sh( returnStdout: true, script: """
                set +x
                kubectl -n ${namespace} get po -oyaml | yq -o=json '.items[] | select(.metadata.name | contains("${stsName}-0")) | {"nodeName": .spec.nodeName, "podStatus": .status.phase}'
            """).trim()
            
            def podInfo = readJSON text: podInfoJson
            def stsNodesPlacement = podInfo.nodeName
            def podStatus = podInfo.podStatus

            def isTheNodeInCordon = sh(returnStdout: true, script: """
                set +x
                kubectl get no -oyaml | yq '.items[] | select(.metadata.name == "${stsNodesPlacement}") | .spec.unschedulable'
            """).trim()

            def currentTime = System.currentTimeMillis()

            // wait until pod is running in the new node
            if (isTheNodeInCordon == "true" && podStatus == "Running") {
              echo "WARNING: ${stsName} in namespace ${namespace} in old node"
            } else if (isTheNodeInCordon == "null" && podStatus == "Running") {
              echo "StatefulSet ${stsName} in namespace ${namespace} is running."

              slackMessage = "Pod ${stsName} is running in the new node"
              sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
              break
            } else if (podStatus != "Running") {
              echo "Pod ${stsName} is not running yet"
            }

            // Check if pod has been pending for more than 5 minutes
            if (!reminderSent && (currentTime - startTime > maxWaitMillis)) {
                slackMessage = "Pod ${stsName} has been pending for over 5 minutes!"
                sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")

                reminderSent = true

                // Ask user whether to continue or skip
                def userInput = input(
                    id: 'skipPodStep',
                    message: "Pod ${stsName} is still pending for more than 5 minutes. Do you want to continue or skip this StatefulSet?",
                    parameters: [
                        [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Skip current StatefulSet and move to the next?', name: 'Skip StatefulSet']
                    ]
                )

                // If approved, break the loop and move to the next StatefulSet
                if (userInput) {
                    echo "Skipping StatefulSet ${stsName} and moving to the next one."
                    slackMessage = "Pod ${stsName} has been pending for over 5 minutes!"
                    sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")

                    break
                } else {
                    echo "Proceeding with the current StatefulSet after the 5-minute wait."
                }
            }
        }
    }
}

def restartStatefulSets(stsList) {
    stsList.each { line ->
        def (namespace, stsName, _) = line.split('---')

        echo "Restarting StatefulSet ${stsName} in namespace ${namespace}..."
        sh "kubectl -n ${namespace} rollout restart sts ${stsName}"
    }
}

def replicaCounter(namespace, numberReplica) {
  return sh(returnStdout: true, script: """
    kubectl get deployments -n ${namespace} -o yaml | yq -r '.items[] | select(.spec.replicas == ${numberReplica}) | .metadata.name' | paste -sd ' '
  """).trim()
}

def replicaScaler(namespace, numberReplica, deployments) {
  if (deployments == null || deployments == "") {
    echo "INFO - No deployments. Skipping scale up"
  } else {
    return sh(returnStdout: true, script: """
      kubectl scale deployments -n ${namespace} --replicas=${numberReplica} ${deployments}
    """).trim()
  }
}

def getReplicasOnDeployment = {namespace, deploymentName ->
  return sh(returnStdout: true, script: """
    kubectl -n ${namespace} get deployment ${deploymentName} -oyaml | yq ".spec.replicas"
  """).trim().toInteger()
}

def getMinHpaReplicas = {namespace, deploymentName ->
  return sh(returnStdout: true, script: """
    kubectl -n ${namespace} get hpa ${deploymentName} -oyaml | yq ".spec.minReplicas"
  """).trim().toInteger()
}

def restartDeploymentOnNamespace = {namespace, deploymentName ->
    sh "kubectl -n ${namespace} rollout restart deployment ${deploymentName}"
}

def getPodCountEmissary = {timeThreshold, deployment, isWebSocket ->
  def filter = isWebSocket ? '.metadata.name | contains("websocket")' : '.metadata.name | contains("websocket") | not'
  return sh(returnStdout: true, script: """
      set +x
      kubectl -n emissary get po -o yaml | yq -r '
      [ .items[] 
      | select(
          (${filter}) 
          and (.status.phase == "Running") 
          and (.status.startTime > "${timeThreshold}")
      ) | .metadata.name] 
      | length'
  """).trim().toInteger()
}

def waitForReplicas = {timeThreshold, targetReplicas, currentReplicas, maxWaitMillis, deploymentName, slackChannel, slackThread ->
  def startTime = System.currentTimeMillis()
  def reminderSent = false

  while (currentReplicas < targetReplicas) {
      currentReplicas = getPodCountEmissary(timeThreshold, deploymentName, false)
      echo "Scaling.. ${deploymentName} ${currentReplicas}/${targetReplicas}"
      def currentTime = System.currentTimeMillis()
      // def elapsedTime = currentTime - startTime
      if (!reminderSent && (currentTime - startTime > maxWaitMillis)) {
          slackMessage = "${deploymentName} replica (${currentReplicas}/${targetReplicas}) are pending for 5 minutes"

          sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")
          reminderSent = true
      }
  }
  slackMessage = "${deploymentName} replica (${currentReplicas}/${targetReplicas}) are running"
  sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
}

def slackThreadParser(url) {
    def regex = ~/\/archives\/[A-Za-z0-9]+\/p(\d{16})/
    def matcher = (url =~ regex)

    if (matcher) {
        def timestamp = matcher[0][1]
        return timestamp[0..9] + '.' + timestamp[10..-1]
    } else {
        return null
    }
}

def slackChannelParser(url) {
    def regex = ~/\/archives\/([A-Za-z0-9]+)\/p/
    def matcher = (url =~ regex)

    if (matcher) {
        return matcher[0][1]
    } else {
        return null
    }
}

def checkRedisStatus() {
  return sh(returnStdout: true, script: """
    kubectl -n redis get po redis-master-0 -oyaml 2>/dev/null | yq '.status.phase | contains("Running")'
  """).trim().toBoolean()
}

def checkRedisVersion(version) {
  return sh(returnStdout: true, script: """
    kubectl -n flux-system get ks redis -oyaml 2>/dev/null | yq '.spec.path | contains("${version}")' 
  """).trim().toBoolean()
}

def dumpDataRedis() {
  def getResponseCmd = sh (returnStdout: true, script: """
    kubectl -n redis exec -t redis-master-0 -- redis-cli SAVE
  """).trim()

  if (getResponseCmd == "OK") {
    echo "Redis data dumped"
  } else {
    def userInput = input(
      id: 'userInput', 
      message: 'Redis 17 is not running. Please dump redis data manually and ensure redis is running before continue, otherwise confirm', 
      parameters: [
        [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
      ]
    )

    if(!userInput) {
      echo "Skip dump data redis manually"
    }
  }
}

def sendReminderSlackMessage(slackChannel, slackThread, message, severity) {
  switch(severity) {
    case "pending":
      emoji = ":loading2:"
    break
    case "info":
      emoji = ":green-check-mark:"
    break
    default:
      emoji = ":green-check-mark:"
    break
  }
  withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
    // POST
    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
    def postData =  [
      channel: "${slackChannel}",
      blocks: [
          [
              type: "section",
              fields: [
                  [
                      type: "mrkdwn",
                      text: "${emoji} ${message}"
                  ]
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

def confirmUser(message) {
    return input(
        id: 'userInput', 
        message: message, 
        parameters: [
            [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
        ]
    )
}


String targetEnvironmentName = params.targetEnvironmentName
String asgTagName = params.asgTagName
Boolean replaceNodes = params.replaceNodes
Boolean cordonNodes = params.cordonNodes
Boolean skipRolloutSts = params.skipRolloutSts
Boolean skipRolloutEmissary = params.skipRolloutEmissary
Boolean skipRolloutJusticeDeployment = params.skipRolloutJusticeDeployment
Boolean skipRolloutExtendDeployment = params.skipRolloutExtendDeployment
Boolean skipRolloutRemainingDeployment = params.skipRolloutRemainingDeployment
Boolean skipRolloutArmadaServices = params.skipRolloutArmadaServices
String slackThreadUrl = params.slackThreadUrl
String envDirectory
String environmentDir
String tempDir="temp$BUILD_NUMBER"
String timeThreshold
def asgOutput
def asgName
def asgDesiredCapacity // default value ASG before scaling or initial value
def targetAsgDesiredCapacity // target capacity ASG to be scaled
def asgMaxCapacity
def asgInstances
def currentEksVersion
def slackThread = slackThreadParser(slackThreadUrl)
def slackChannel = slackChannelParser(slackThreadUrl)
def asgUrl

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-rollout-worker-node"

node('hosting-agent') {
  container('tool') {
    dir(tempDir){
      stage('Clone iac repository') {
        sshagent(['bitbucket-repo-read-only']) {
          sh """#!/bin/bash
            set -e
            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
            git clone --quiet "git@bitbucket.org:accelbyte/iac.git" || true
            rm -rf iacTemp || true
            cp -R iac iacTemp || true
            chmod -R 777 iacTemp || true
            rm -rf ~/.aws/config || true
          """
        }
      }
      
      stage('Set aws credentials'){
        def (customer, project, environment) = targetEnvironmentName.split('-')
        dir('iacTemp') {
          envDirectory = sh(returnStdout: true, script: """
            clusterDir=\$(find live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep ${environment} | grep -v terragrunt-cache | head -n 1)
            dirname \${clusterDir}
          """
          ).trim()
          awsAccountId = sh(returnStdout: true, script: """
            echo ${envDirectory} | egrep -o '[[:digit:]]{12}'
          """
          ).trim()
          awsRegion = sh(returnStdout: true, script: """
            basename \$(dirname ${envDirectory})
          """
          ).trim()
  
          awsAccessMerged = sh(returnStdout: true, script: """
            set +x
            export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
            \$(aws sts assume-role \\
            --role-arn arn:aws:iam::${awsAccountId}:role/${targetEnvironmentName}-automation-platform \\
            --role-session-name ${targetEnvironmentName} \\
            --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
            --output text))

            export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
            \$(aws sts assume-role \\
            --role-arn arn:aws:iam::${awsAccountId}:role/${targetEnvironmentName}-automation-platform-terraform \\
            --role-session-name ${targetEnvironmentName} \\
            --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
            --output text))
            echo \${AWS_ACCESS_KEY_ID}:\${AWS_SECRET_ACCESS_KEY}:\${AWS_SESSION_TOKEN}
          """
          ).trim()

          def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = awsAccessMerged.split(':')
          env.AWS_ACCESS_KEY_ID = awsAccessKeyId
          env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey
          env.AWS_SESSION_TOKEN = awsSessionToken
          env.AWS_DEFAULT_REGION = awsRegion
          env.AWS_REGION = awsRegion
          sh 'aws sts get-caller-identity --no-cli-pager'
        }
      }

      stage('Generate Kubeconfig') {
        sh """#!/bin/bash
          set -e
          set -o pipefail
          envsubst < ~/.aws/config.template > ~/.aws/config
          aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
        """
      }

      stage('Prepare common variable') {
          currentEksVersion =  sh( returnStdout: true, script: """
            aws eks describe-cluster \
            --name "${targetEnvironmentName}" \
            --region "${awsRegion}" \
            --query "cluster.version" | cut -d'"' -f2 
          """).trim()

          asgOutput = getSpecificAsg(targetEnvironmentName, awsRegion, asgTagName);

          asgName = sh( returnStdout: true, script: """
            set +x
            echo "${asgOutput}" | yq '.asgName'
            """).trim()
          asgDesiredCapacity = sh( returnStdout: true, script: """
            set +x
            echo "${asgOutput}" | yq '.asgDesiredCapacity'
            """).trim().toInteger()
          asgMaxCapacity = sh( returnStdout: true, script: """
            set +x
            echo "${asgOutput}" | yq '.asgMaxCapacity'
            """).trim().toInteger()
          asgInstances = sh( returnStdout: true, script: """
            set +x
            echo "${asgOutput}" | yq '.asgInstances'
            """).trim()
          asgUrl = "https://${awsRegion}.console.aws.amazon.com/ec2/home?region=${awsRegion}#AutoScalingGroupDetails:id=${asgName};view=details"
          
          echo "asgName = ${asgName}"
          echo "asgDesiredCapacity = ${asgDesiredCapacity}"
          echo "asgMaxCapacity = ${asgMaxCapacity}"
          echo "asgInstances = ${asgInstances}"
          echo "asgUrl = ${asgUrl}"

          if(asgName == "" || asgName == "null") {
            echo "ASG Critical On Init not found, exiting"
            exit 1
          }

          echo "slackThread ${slackThread}"
          echo "slackChannel ${slackChannel}"

          if(slackChannel == null && slackThread == null) {
            echo "Slack Channel or Slack Thread is missing"
            exit 1
          }
      }

      stage('Scaling Up ASG') {
        echo "Scale Up ASG"
        if(replaceNodes == false) {
          echo "Skip scale up ASG due value replaceNodes is false"
        } else {
          targetAsgDesiredCapacity = asgDesiredCapacity * 2
          echo "Set Max ASG ${asgName} ${targetAsgDesiredCapacity}"

          while(true) {
            def lastStatusASG = getLastStatusAsg(asgName, awsRegion)

            if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
              echo "Set Max Capacity ASG ${asgName}"
              setAsgMaxCapacity(asgName, awsRegion, targetAsgDesiredCapacity)
              break
            } else {
              echo "INFO - Waiting status scaling activity on ${asgName} to ready"
              sleep(5)
            }
          }

          echo "Set New Protected Instance In ASG ${asgName}"
          while(true) {
            def lastStatusASG = getLastStatusAsg(asgName, awsRegion)

            if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
              echo "asgName = ${asgName}"
              configureProtectedInstancesInAsg(asgName, awsRegion, "--new-instances-protected-from-scale-in")
              break
            } else {
              echo "INFO - Waiting status scaling activity on ${asgName} to ready"
              sleep(5)
            }
          }

          echo "Scale Up ASG ${asgName} to ${targetAsgDesiredCapacity}"
          while(true) {
            def lastStatusASG = getLastStatusAsg(asgName, awsRegion)

            if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
              echo "Scaling activity completed or none in progress for ASG: ${asgName}"

              def maxWaitMillis = 5 * 60 * 1000 // 5 minutes in milliseconds
              def reminderSent = false
              def startTime = System.currentTimeMillis()
              
              while(true) {
                def tryScaleUp = scaleAsg(asgName, awsRegion, targetAsgDesiredCapacity)
                sleep(3)

                if (tryScaleUp.contains("ScalingActivityInProgress")) {
                  echo "Scaling activity is still in progress. Retrying in 10 seconds..."
                  sleep(10) 
                } else {
                  echo "Scaling successful, wait until worker nodes are ready"
                  break
                }

                // Remind 5 minutes ASG to scale
                def currentTime = System.currentTimeMillis()
                if (!reminderSent && (currentTime - startTime > maxWaitMillis)) {
                  slackMessage = "Scaling up ASG ${asgName} over than 5 minutes. Please check <${asgUrl}|Go to ASG!>"
                  sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")

                  reminderSent = true
                  // startTime = currentTime // Reset timer if you want repeated reminders every 5 min
                }
              }
              break 
            } else {
              echo "INFO - Waiting status scaling activity on ${asgName} to ready"
              sleep(5)

              // Remind 5 minutes until worker nodes are ready
              def currentTime = System.currentTimeMillis()
              if (!reminderSent && (currentTime - startTime > maxWaitMillis)) {
                slackMessage = "Scaling up ASG ${asgName} over than 5 minutes. Please check <${asgUrl}|Go to ASG!>"
                sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")

                reminderSent = true
                // startTime = currentTime // Reset timer if you want repeated reminders every 5 min
              }
            }
          }

          sleep(30)

          echo "Verifying worker node ASG ${asgName}"
          while(true) {
            currentInstancesInASG = sh(returnStdout: true, script: """
              set +x
              aws autoscaling describe-auto-scaling-groups \
              --region ${awsRegion} \
              --query "AutoScalingGroups[*]" \
              --output yaml \
              | yq -r '
              .[] | select(
                  .AutoScalingGroupName == "${asgName}" and 
                  .Instances[].HealthStatus == "Healthy" and 
                  .Instances[].LifecycleState == "InService"
              ) | .Instances | length'
            """).trim()

            def currentNodeCount = (asgTagName == "critical-on-init") ?
              sh(returnStdout: true, script: """
                set +x
                kubectl get nodes -o yaml | yq '[
                  .items[] | select(.metadata.labels."accelbyte.io/workload" == "CriticalOnInit")
                  ] | length'
              """).trim() : 
              sh(returnStdout: true, script: """
                set +x
                kubectl get no -o yaml | yq '[
                  .items[] | select(
                    .metadata.labels."beta.kubernetes.io/instance-type" == "t3a.2xlarge" and
                    (.metadata.labels."accelbyte.io/workload" // null) == null
                  ) | .metadata.name
                ] | length'
              """).trim()
            
            if (currentInstancesInASG.toInteger() == targetAsgDesiredCapacity.toInteger() && currentNodeCount.toInteger() == targetAsgDesiredCapacity.toInteger()) {
              slackMessage = "New ${asgTagName} nodes are healthy and serving"
              sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
              break
            } else {
              sleep 30
            }
          }
        }
      }

      stage('Cordon all nodes') {
        if (cordonNodes) {
          echo "Start cordon old worker nodes"
          oldWorkerNodesList = sh(returnStdout: true, script: """
            kubectl get no -oyaml | yq '.items[] | select(.status.nodeInfo.kubeletVersion | contains("${currentEksVersion}") | not) | .metadata.name'
          """).trim()

          if (oldWorkerNodesList) {
            def oldWorkerNodes = oldWorkerNodesList.split('\n')
            def nodesString = oldWorkerNodes.join(' ')

            sh """
              kubectl cordon ${nodesString}
            """
          } else {
            def userInput = input(
              id: 'userInput', 
              message: 'All worker nodes are in the latest version. There are no nodes to cordon', 
              parameters: [
                [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
              ]
            )

            if(!userInput) {
              error "Canceled"
            }
          }
        } else {
          echo "Skip cordon nodes"
        }
      }

      stage('Rollout critical-on-init services') {
        if (asgTagName != "critical-on-init") {
          echo "Skip Rollout Critical-on-init services"
        } else {
          echo "Rollout restart karpenter services"
          sh "kubectl -n karpenter rollout restart deployment"

          echo "Rollout restart flux-system services"
          fluxSystemDeployments = sh( returnStdout: true, script: """
            kubectl -n flux-system get deploy -oyaml | yq '.items[] | select(.metadata.name | contains("controller")) | .metadata.name'
          """).trim().split('\n')

          for (fluxSystemDeployment in fluxSystemDeployments) {
            hasPVC = sh( returnStdout: true, script: """
              kubectl -n flux-system get deploy ${fluxSystemDeployment} -o yaml | yq '[.spec.template.spec.volumes[] | select(has("persistentVolumeClaim"))] | length > 0'
            """).trim()

            if (hasPVC) {
              while(true) {
                sh "kubectl -n flux-system scale deployment ${fluxSystemDeployment} --replicas=0"
                sleep(3)
                isScaledDown = sh( returnStdout: true, script: """
                  kubectl -n flux-system get deployment ${fluxSystemDeployment} -o yaml | yq '.status.availableReplicas'
                """).trim()

                if(isScaledDown == null || isScaledDown == "null" || isScaledDown == 0) {
                  sh "kubectl -n flux-system scale deployment ${fluxSystemDeployment} --replicas=1"
                  break
                } else {
                  sleep(5)
                }
              }
            } else {
              sh "kubectl -n flux-system rollout restart deployment ${fluxSystemDeployment}"
            }
          }
        }
      }

      stage('Verifying critical-on-init services') {
        if (asgTagName != "critical-on-init") {
          echo "Skip Verifying critical-on-init services"
        } else {
          echo "Verifying karpenter services"
          karpenterReplicasCount = sh( returnStdout: true, script: """
            set +x
            kubectl -n karpenter get deploy karpenter -oyaml | yq '.status.readyReplicas'
          """).trim()

          while(true) {
            karpenterNodesPlacement = sh( returnStdout: true, script: """
              set +x
              kubectl -n karpenter get po -oyaml | yq '.items[] | .spec.nodeName'
            """).trim().split('\n')

            for (karpenterNode in karpenterNodesPlacement) {
              isKarpenterNodeInCordon = sh( returnStdout: true, script: """
                set +x
                kubectl get no -oyaml | yq '.items[] | select(.metadata.name == "${karpenterNode}") | .spec.unschedulable'
              """).trim()

              if (isKarpenterNodeInCordon == "true" || isKarpenterNodeInCordon == true) {
                echo "There is karpenter pods in old node"
                sh "kubectl -n karpenter rollout restart deployment"

                slackMessage = "There are karpenter pods running in the old node"
                sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")
              } else if (isKarpenterNodeInCordon == "null" || isKarpenterNodeInCordon == "false") {
                echo "Karpenter placed in new node"

                // slackMessage = "Karpenter pods are running in the new node"
                // sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
              }
              sleep(3)
            }
            break
          }

          echo "Verifying flux-system services"
          fluxSystemDeployments = sh( returnStdout: true, script: """
            set +x
            kubectl -n flux-system get deploy -oyaml | yq '.items[] | select(.metadata.name | contains("controller")) | .metadata.name'
          """).trim().split('\n')

          for (fluxSystemDeployment in fluxSystemDeployments) {
            fluxSystemNodesPlacement = sh( returnStdout: true, script: """
              set +x
              kubectl -n flux-system get pod -oyaml | yq '.items[] | select(.metadata.labels.app == "${fluxSystemDeployment}") | .spec.nodeName'
            """).trim()

            isFluxSystemPodsInCordon = sh( returnStdout: true, script: """
              set +x
              kubectl get no -oyaml | yq '.items[] | select(.metadata.name == "${fluxSystemNodesPlacement}") | .spec.unschedulable'
            """).trim()

            if (isFluxSystemPodsInCordon == true || isFluxSystemPodsInCordon == "true") {
              echo "WARNING: ${fluxSystemDeployment} in old node"
              
              slackMessage = "${fluxSystemDeployment} pods running in the old node"
              sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")
            } else {
              echo "Pod ${fluxSystemDeployment} is running in the new node"
              
              // slackMessage = "Pod ${fluxSystemDeployment} is running in the new node"
              // sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
            }
          }
        }
      }

      stage('Rollout Restart Statefulset') {
        if(params.skipRolloutSts) {
          echo "Skip Rollout Statefulset"
        } else {
          def userInput = input(
            id: 'userInput', 
            message: 'Are you sure to restart Statefulset...', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInput) {
            echo "Skipping restart Statefulset services"
          }

          def statefulSetsWithPVC = getStatefulSets(true)
          def statefulSetsWithoutPVC = getStatefulSets(false)

          if (statefulSetsWithPVC) {
              scaleStatefulSetIndividually(statefulSetsWithPVC, slackThread, slackChannel)
          }

          if (statefulSetsWithoutPVC) {
              restartStatefulSets(statefulSetsWithoutPVC)
          }
        }
      }

      stage('Rollout Restart Emissary') {
        if(params.skipRolloutEmissary) {
          echo "Skip Rollout restart emissary services"
        } else {
          def userInput = input(
            id: 'userInput', 
            message: 'Are you sure to restart emissary-ingress...', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInput) {
            echo "Skipping restart emissary-ingress services"
          }

          timeThreshold = sh(returnStdout: true, script: "date -u +\"%Y-%m-%dT%H:%M:%SZ\"").trim()

          def emissaryDeployments = sh(returnStdout: true, script: """
            kubectl -n emissary get deploy -oyaml | yq '.items[] | select(.metadata.name | contains("emissary")) | .metadata.name' | paste -sd ' '
          """).trim()

          def maxWaitMillis = 5 * 60 * 1000 // 5 minutes in milliseconds

          if (emissaryDeployments.contains("websocket")) {
            def emissaryIngressReplica = getMinHpaReplicas("emissary", "emissary-ingress")
            def emissaryIngressWebsocketReplica = getMinHpaReplicas("emissary", "emissary-ingress-websocket")

            restartDeploymentOnNamespace("emissary", "emissary-ingress")
            restartDeploymentOnNamespace("emissary", "emissary-ingress-websocket")

            def currentEmissaryIngressReplica = 0
            waitForReplicas(timeThreshold, emissaryIngressReplica, currentEmissaryIngressReplica, maxWaitMillis, "emissary-ingress", slackChannel, slackThread)
            
            def currentEmissaryIngressWebsocketReplica = 0
            waitForReplicas(timeThreshold, emissaryIngressWebsocketReplica, currentEmissaryIngressWebsocketReplica, maxWaitMillis, "emissary-ingress-websocket", slackChannel, slackThread)
          } else {
            def emissaryIngressReplica = getMinHpaReplicas("emissary", "emissary-ingress")
            restartDeploymentOnNamespace("emissary", "emissary-ingress")

            def currentEmissaryIngressReplica = 0
            waitForReplicas(timeThreshold, emissaryIngressReplica, currentEmissaryIngressReplica, maxWaitMillis, "emissary-ingress", slackChannel, slackThread)
          }
        }
      }

      stage('Rollout Restart Justice Deployment') {
        if(params.skipRolloutJusticeDeployment) {
          echo "Skip Rollout Justice services"
        } else {
          echo "Rollout Restart Justice Deployment"

          def countReplica0 = replicaCounter("justice", 0)
          def countReplica1 = replicaCounter("justice", 1)
          def countReplica2 = replicaCounter("justice", 2)

          def userInput = input(
            id: 'userInput', 
            message: 'Before continue, please ensure all statefulset are running', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInput) {
            echo "Skipping restart justice deployment"
          }

          sh "kubectl -n justice rollout restart deployment"

          sleep(30)
          def scaleReplica0 = replicaScaler("justice", 0, countReplica0)
          def scaleReplica1 = replicaScaler("justice", 1, countReplica1)
          def scaleReplica2 = replicaScaler("justice", 2, countReplica2)
        }
      }

      stage('Rollout Restart Extend Deployment') {
        def crossplaneReplicas = sh(returnStdout: true, script: """
          kubectl -n crossplane-system get deploy crossplane -oyaml | yq '.status.readyReplicas // 0' 
        """).trim().toInteger()
        def countExtNamespace = sh(returnStdout: true, script: """
          kubectl get ns -o yaml | yq '[.items[] | select(.metadata.name | contains("ext-"))] | length'
        """).trim().toInteger()

        if(params.skipRolloutExtendDeployment || (crossplaneReplicas < 1 && countExtNamespace < 1)) {
          echo "Skip Rollout Extend Deployment"
        } else {
          def userInput = input(
            id: 'userInput', 
            message: 'Rollout Restart Extend Deployment', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInput) {
            echo "Skip Rollout Extend Deployments"
          }

          echo "Rollout restart extend deployments"
          sh "kubectl -n crossplane-system rollout restart deploy"
          extendNamespacesWithActiveReplica = sh( returnStdout: true, script: """
            kubectl get deployment -A -o yaml | yq -r '
              .items[] | 
              select(
              .metadata.name | contains("ext-") and 
              .status.readyReplicas != null) | 
              .metadata.namespace
            ' | uniq
          """).trim().split('\n')
          
          if (extendNamespacesWithActiveReplica[0] != "") {
            for (namespace in extendNamespacesWithActiveReplica) {			
              sh """
                kubectl -n ${namespace} rollout restart deployment
              """
            }
          } else {
            echo "No active extend deployments found"
          }
        }
      }

      stage('Rollout Restart Remaining Deployment') {
        if(params.skipRolloutRemainingDeployment) {
          echo "Skip Rollout Remaining Deployment (except justice|justice-play|karpenter|flux|emissary|crossplane|ext-)"
        } else {
          def userInput = input(
            id: 'userInput', 
            message: 'Rollout Restart Remaining Deployment (except justice|justice-play|karpenter|flux|emissary|crossplane|ext-)', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInput) {
            echo "Skip Rollout Remaining Deployment (except justice|justice-play|karpenter|flux|emissary|crossplane|ext-)"
          }
          echo "Rollout restart all deployments"
          otherActiveNamespaceWithDeployments = sh(returnStdout: true, script: """
            kubectl get deployment -A -o yaml | yq '
                .items[] |
                select(
                    .metadata.name | contains("ext-") | not and
                    .metadata.namespace | contains("emissary") | not and
                    .metadata.namespace != "crossplane-system" and
                    .metadata.namespace != "flux-system" and
                    .metadata.namespace != "justice" and
                    .metadata.namespace != "justice-play" and
                    .metadata.namespace != "karpenter" and
                    .status.readyReplicas != null
                ) |
                .metadata.namespace
            ' | uniq
          """
          ).trim().split('\n')

          for (namespace in otherActiveNamespaceWithDeployments) {			
            sh """
              kubectl -n ${namespace} rollout restart deployment
            """
          }

          sleep(10)
        }
      }

      stage('Rollout Restart justice-play') {
        countConsulServerReplicas = sh(returnStdout: true, script: """
          kubectl -n justice-play get sts consul-server -oyaml 2>/dev/null | yq '.status.replicas // 0'
        """).trim().toInteger()
        countNomadServerReplicas = sh(returnStdout: true, script: """
          kubectl -n justice-play get sts nomad-server -oyaml 2>/dev/null | yq '.status.replicas // 0'
        """).trim().toInteger()

        if(params.skipRolloutArmadaServices || (countConsulServerReplicas < 4 && countNomadServerReplicas < 4)) {
          echo "Skip Rollout Armada Services services"
        } else {
          echo "Rollout restart justice-play (armada services)"

          def userInputConsulServer = input(
            id: 'userInput', 
            message: 'Are you sure to restart Armada services, please backup cm, secret and sa before continue...', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInputConsulServer) {
            echo "Skipping restart armada services"
          }
          echo "Restarting Consul Server"

          readyReplicasConsulServer = sh( returnStdout: true, script: """
            kubectl -n justice-play get sts consul-server -oyaml | yq '.status.readyReplicas'
          """).trim()

          sh """
            kubectl -n justice-play rollout restart sts consul-server
          """

          while(true) {
            currentReplicasConsulServer = sh( returnStdout: true, script: """
              kubectl -n justice-play get sts consul-server -oyaml | yq '.status.currentReplicas'
            """).trim()

            if (readyReplicasConsulServer == currentReplicasConsulServer) {
              break
            }

            sleep(10)
          }

          def userInputNomadServer = input(
            id: 'userInput', 
            message: 'Are you sure to restart Armada services, please backup cm, secret and sa before continue...', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInputNomadServer) {
            echo "Skipping restart armada services"
          }

          sh """
            echo "Restarting Nomad Server"
            kubectl -n justice-play rollout restart sts nomad-server
          """
          
          sleep(30)
        }
      }

      stage('Scale Down ASG') {
        if(replaceNodes == false) {
          echo "Skip scale down ASG due value replaceNodes is false"
        } else {
          // Ask confirmation 
          def userInputScaleDownASG = confirmUser("Are you sure to scale down ${asgName} to ${asgDesiredCapacity}, please ensure no workload running in the old node...")
          if(!userInputScaleDownASG) {
            echo "Skip Scale Down ASG"
          }

          // Configure new instance is not protected at first spawning EC2 in ASG
          while(true) {
            def lastStatusASG = getLastStatusAsg(asgName, awsRegion)

            if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
              echo "set no-new-protected-instance asgName = ${asgName}"
              configureProtectedInstancesInAsg(asgName, awsRegion, "--no-new-instances-protected-from-scale-in")
              break
            } else {
              echo "INFO - Waiting status scaling activity on ${asgName} to ready"
              sleep(5)
            }
            break
          }

          while(true) {
            def lastStatusASG = getLastStatusAsg(asgName, awsRegion)

            if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
              echo "Scaling activity completed or none in progress for ASG: ${asgName}"

              slackMessage = "Old ${asgTagName} nodes are being removed"
              sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
              
              def startTime = System.currentTimeMillis()
              def maxWaitMillis = 5 * 60 * 1000 // 5 minutes in milliseconds
              def reminderSent = false

              while(true) {
                def tryScaleDown = scaleAsg(asgName, awsRegion, asgDesiredCapacity)
                sleep(3)

                // Limit 5 minutes to wait ASG to scaling
                def currentTime = System.currentTimeMillis()
                if (!reminderSent && (currentTime - startTime > maxWaitMillis)) {
                  slackMessage = "Scaling Down ASG ${asgName} over than 5 minutes. Please check <${asgUrl}|Go to ASG!>"
                  sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")

                  reminderSent = true
                  // startTime = currentTime // Reset timer if you want repeated reminders every 5 min
                }

                if (tryScaleDown.contains("ScalingActivityInProgress")) {
                  echo "Scaling activity is still in progress. Retrying in 10 seconds..."
                  sleep(10)
                } else {
                  echo "Scaling successful, exiting loop."
                  break
                }
              }
            } else {
              echo "INFO - Waiting status scaling activity on ${asgName} to ready"
              sleep(5)
            }
            break
          }

          while(true) {
            currentProtectedInstancesInASG = sh(returnStdout: true, script: """
              set +x
              aws autoscaling describe-auto-scaling-groups \
              --auto-scaling-group-name ${asgName} \
              --region ${awsRegion} \
              --output yaml | yq '.AutoScalingGroups[].Instances[] | select(.ProtectedFromScaleIn == true) | .InstanceId' 
            """).trim()

            countProtectedInstancesInASG = sh(returnStdout: true, script: """
              set +x
              aws autoscaling describe-auto-scaling-groups \
              --auto-scaling-group-name ${asgName} \
              --region ${awsRegion} \
              --output yaml | yq '[.AutoScalingGroups[].Instances[] | select(.ProtectedFromScaleIn == true)] | length'
            """).trim()

            if (countProtectedInstancesInASG == "") {
              echo "No protected instance in ASG ${asgName}"
              break
            } else {
              echo "currentProtectedInstancesInASG = ${currentProtectedInstancesInASG}"
              echo "countProtectedInstancesInASG = ${countProtectedInstancesInASG}"
              if (countProtectedInstancesInASG.toInteger() > 0) {
                def protectedInstanceIds = currentProtectedInstancesInASG.split("\n")
                for (protectedInstanceId in protectedInstanceIds) {
                  echo "Remove scale-in protection ${protectedInstanceId} in ASG"
                  sh """
                    aws autoscaling set-instance-protection \
                    --instance-ids "${protectedInstanceId}" \
                    --auto-scaling-group-name ${asgName} \
                    --no-protected-from-scale-in \
                    --region ${awsRegion}
                  """
                  sleep 3
                }
                break
              } else {
                echo "No protected instance in ASG ${asgName}"
                break
              }
            }
            break
          }
          
          echo "Revert ASG ${asgName} Max Capacity to ${asgMaxCapacity}"
          def startTime = System.currentTimeMillis()
          def maxWaitMillis = 7 * 60 * 1000 // 7 minutes in milliseconds
          def reminderSent = false

          while(true) {
            def lastStatusASG = getLastStatusAsg(asgName, awsRegion)
            if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
              setAsgMaxCapacity(asgName, awsRegion, asgMaxCapacity)
              break
            } else {
              echo "INFO - Waiting status scaling activity on ${asgName} to ready"
              sleep(5)
              // Limit another 5 minutes to Worker Nodes ready
              def currentTime = System.currentTimeMillis()

              if (!reminderSent && (currentTime - startTime > maxWaitMillis)) {
                slackMessage = "Scaling Down ASG ${asgName} over than 7 minutes. Please check <${asgUrl}|Go to ASG!>"
                sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "pending")

                reminderSent = true
                // startTime = currentTime // Reset timer if you want repeated reminders every 5 min
              }
            }
          }
        }
      }

      stage('Send Notification') {
        slackMessage = "Rollout worker nodes are complete. Please verify services health"
        sendReminderSlackMessage(slackChannel, slackThread, slackMessage, "info")
      }
    }
  }
}
