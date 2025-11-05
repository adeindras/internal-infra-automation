import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.plugins.git.extensions.impl.SparseCheckoutPath
import jenkins.plugins.http_request.ResponseContentSupplier
import java.security.MessageDigest

properties(
  [
    parameters([
      string(defaultValue: '', name: 'targetEnvironmentName'),
      string(defaultValue: '', name: 'tierInfo'),
      string(defaultValue: '', name: 'slackThread'),
      string(defaultValue: '', name: 'tierSetup')
    ])
  ]
)

// constants
BITBUCKET_CREDS_ID = 'bitbucket-repo-read-only'
DEPLOYMENT_REPO_SLUG = "deployments"
ENVIRONMENT_NAME = params.targetEnvironmentName
def tierSetup = params.tierSetup
def tierInfoData = JsonOutput.toJson(params.tierInfo)
def stateStatus
def latestMasterCommitHash = ""
def buildStopped = false
def slackThread = params.slackThread
def ENV_PATH
def service_name = "test"
def OK_count = 0
def NOT_OK_count = 0

node('deploy-agent') {
  container('tool') {
    stage('Pipeline Pre-Check') {
      String resultNotBuilt = 'NOT_BUILT'
      if (tierSetup == '') {
        currentBuild.result = resultNotBuilt
        buildStopped = true
        error('tierSetup is empty. Aborting the build')
      }
      if (targetEnvironmentName == '') {
        currentBuild.result = resultNotBuilt
        buildStopped = true
        error('targetEnvironmentName is empty. Aborting the build')
      }
      if (tierInfo == '') {
        currentBuild.result = resultNotBuilt
        buildStopped = true
        error('tierInfo is empty. Aborting the build')
      }
      if (slackThread == '') {
        currentBuild.result = resultNotBuilt
        buildStopped = true
        error('slackThread is empty. Aborting the build')
      }
      if (WORKSPACE.contains('DEVELOPMENT')) {
        slackChannel = 'C07UY55SE20'
      } else {
        slackChannel = 'C080SRE92NA'
      }
      ENV_PATH = ENVIRONMENT_NAME.replace('-','/')

    }
    if (!buildStopped) {
      stage('Infra Check Init'){
        def message = "--- INFRASTRUCTURE CHECK ---"
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          generateSlackMessage(message, slackThread, slackToken)
        }
      }
      stage('Init Process Checker') {
        createBanner("STAGE: Initializing..")
        currentBuild.displayName = "#${BUILD_NUMBER} - validation - ${ENVIRONMENT_NAME}"
      }
      stage('Check Cluster Info') {
        createBanner("STAGE: Check cluster ${ENVIRONMENT_NAME} information")
        withCredentials([string(credentialsId: "internal-deploy-tool-token-0", variable: 'bbAccessToken')]) {
          def cmd = '''
            # get latest commit from master
            LATEST_MASTER_COMMIT_HASH="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/deployments/commits/master?pagelen=1" | jq -r '.values[0].hash')"
            echo ${LATEST_MASTER_COMMIT_HASH}
          '''
          latestMasterCommitHash = sh(returnStdout: true, script: cmd).trim()
        }
        dir("tierTemp${BUILD_NUMBER}") {
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

          // Putting tierInfo Data into a temporary json file
          sh """
            echo "${tierInfoData}" > tempJsonInfo.json
            cat tempJsonInfo.json
          """
        }
      }
      stage('Get cluster information') {
          createBanner("STAGE: Get cluster information")
          // get from file cluster-information.env in each cluster directory
          dir("tierTemp${BUILD_NUMBER}/$ENV_PATH") {
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
            echo ${env.EKS_CLUSTER_NAME} ${env.AWS_REGION}
            set -e
            set -o pipefail
            envsubst < ~/.aws/config.template > ~/.aws/config
            # aws sts get-caller-identity
            aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_REGION}
          """
      }
      stage("Checking Karpenter") {
        def slackMessage
        def serviceName = "Karpenter"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = karpenterChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }
        }
      }
      stage("Checking Linkerd") {
        def slackMessage
        def serviceName = "Linkerd"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = linkerdChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }
        }
      }
      stage("Checking Emissary") {
        def slackMessage
        def serviceName = "Emissary"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = emissaryChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }

        }
      }
      stage("Checking Websocket") {
        def slackMessage
        def serviceName = "Websocket"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = websocketChecker(serviceName)
          checkerStatus = checkerOutput.checkerStatus
          if (checkerStatus != "SKIP"){
            OK_count += checkerOutput.OK_count
            NOT_OK_count += checkerOutput.NOT_OK_count
          }

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }

        }
      }
      stage("Checking ALB") {
        def slackMessage
        def serviceName = "ALB"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = albChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }

        }
      }
      stage('Obv Check Init'){
        def message = "--- OBSERVABILITY CHECK ---"
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          generateSlackMessage(message, slackThread, slackToken)
        }
      }
      stage("Checking Otelcollector") {
        def slackMessage
        def serviceName = "Otelcollector"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = otelcollectorChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }

        }
      }
      stage("Checking Fluentd") {
        def slackMessage
        def serviceName = "Fluentd"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = fluentdChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }

        }
      }
      stage("Checking KSM") {
        def slackMessage
        def serviceName = "Kube-State-Metrics"
        def checkerStatus
        createBanner("STAGE: Validating ${serviceName} State")
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          slackMessage = initMessage(serviceName, slackThread, slackToken)
        }
        dir("tierTemp${BUILD_NUMBER}") {
          def checkerOutput = ksmChecker(serviceName)
          OK_count += checkerOutput.OK_count
          NOT_OK_count += checkerOutput.NOT_OK_count
          checkerStatus = checkerOutput.checkerStatus

          withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
            updateMessage(serviceName, slackMessage, slackToken, checkerStatus)
          }

        }
      }
      stage('Generating Report'){
        def totalCase = OK_count+NOT_OK_count
        def percentCase = calculatePercentage(OK_count,totalCase)
        def testMsg = "Total check : ${OK_count}/${totalCase} (${percentCase}%)"
        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
          pushReport(slackThread, slackToken)
          generateSlackMessage(testMsg, slackThread, slackToken)
        }
      }
    }
  }
}

def karpenterChecker(String serviceName){
  def sta_helm_ver
  def sta_helm_mem_lmt 
  def sta_helm_mem_req
  def sta_helm_cpu_req 
  def sta_prov_def_mem 
  def sta_prov_def_cpu
  def sta_prov_def_type 
  def sta_prov_crt_mem 
  def sta_prov_crt_cpu 
  def sta_prov_crt_type
  def sta_pod_count 
  def sta_pod_ready 
  def sta_patch
  def OK_count = 0
  def NOT_OK_count = 0
  def checkerStatus = "TRUE"

  // Getting all cluster info
  def helm_ver = sh(returnStdout: true, quiet: true, script: "kubectl get hr karpenter -n karpenter -oyaml | yq '.spec.chart.spec.version'").trim()
  def helm_mem_lmt = sh(returnStdout: true, quiet: true, script: "kubectl get hr karpenter -n karpenter -oyaml | yq '.spec.values.controller.resources.limits.memory'").trim()
  def helm_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get hr karpenter -n karpenter -oyaml | yq '.spec.values.controller.resources.requests.memory'").trim()
  def helm_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get hr karpenter -n karpenter -oyaml | yq '.spec.values.controller.resources.requests.cpu'").trim()
  def prov_def_mem = sh(returnStdout: true, quiet: true, script: "kubectl get provisioner default -oyaml | yq '.spec.limits.resources.memory'").trim()
  def prov_def_cpu = sh(returnStdout: true, quiet: true, script: "kubectl get provisioner default -oyaml | yq '.spec.limits.resources.cpu'").trim()
  def prov_def_type = sh(returnStdout: true, quiet: true, script: """ kubectl get provisioner default -ojson | jq -c '.spec.requirements[] | select(.key == "karpenter.k8s.aws/instance-category") | .values' """).trim()
  def prov_crt_mem = sh(returnStdout: true, quiet: true, script: "kubectl get provisioner critical-workload -oyaml | yq '.spec.limits.resources.memory'").trim()
  def prov_crt_cpu = sh(returnStdout: true, quiet: true, script: "kubectl get provisioner critical-workload -oyaml | yq '.spec.limits.resources.cpu'").trim()
  def prov_crt_type = sh(returnStdout: true, quiet: true, script: """kubectl get provisioner critical-workload -ojson | jq -c '.spec.requirements[] | select(.key == "karpenter.k8s.aws/instance-category") | .values' """).trim()
  def pod_count = sh(returnStdout: true, quiet: true, script: """ kubectl get deploy karpenter -n karpenter --no-headers -o custom-columns=":status.replicas" """).trim()
  def pod_ready = sh(returnStdout: true, quiet: true, script: """ kubectl get deploy karpenter -n karpenter --no-headers -o custom-columns=":status.readyReplicas" """).trim()
  def mmv2_patch_1 = sh(returnStdout: true, quiet: true, script: """ kubectl get deploy justice-matchmaking-v2 -n justice -oyaml | yq '.spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[] | .matchExpressions[] | select(.key == "karpenter.k8s.aws/instance-family") | .values' """).trim()
  def mmv2_patch_2 = sh(returnStdout: true, quiet: true, script: """ kubectl get deploy justice-matchmaking-v2 -n justice -oyaml | yq '.spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[] | .matchExpressions[] | select(.key == "karpenter.sh/capacity-type") | .values' """).trim()

  // Separate tierInfo data
  def val_helm_ver = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].helmchart_ver' tempJsonInfo.json """).trim()
  def val_helm_mem_lmt = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].karpenter_mem_limit' tempJsonInfo.json """).trim()
  def val_helm_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].karpenter_mem_req' tempJsonInfo.json """).trim()
  def val_helm_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].karpenter_cpu_req' tempJsonInfo.json """).trim()
  def val_prov_def_mem = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].default_mem_limit' tempJsonInfo.json """).trim()
  def val_prov_def_cpu = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].default_cpu_limit' tempJsonInfo.json """).trim()
  def val_prov_def_type = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].default_instance_type' tempJsonInfo.json """).trim()
  def val_prov_crt_mem = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].critical_mem_limit' tempJsonInfo.json """).trim()
  def val_prov_crt_cpu = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].critical_cpu_limit' tempJsonInfo.json """).trim()
  def val_prov_crt_type = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].critical_instance_type' tempJsonInfo.json """).trim()
  def val_pod_count = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].pod_count' tempJsonInfo.json """).trim()
  def val_mmv2_patch_1 = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].custom_patch_1' tempJsonInfo.json """).trim()
  def val_mmv2_patch_2 = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "karpenter") | .data[].custom_patch_2' tempJsonInfo.json """).trim()

  // Checking Helm value
  if (helm_ver == val_helm_ver){
    sta_helm_ver = "OK"
    OK_count++
  }
  else if (helm_ver != val_helm_ver){
    sta_helm_ver = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (helm_mem_lmt == val_helm_mem_lmt){
    sta_helm_mem_lmt = "OK"
    OK_count++
  }
  else if (helm_mem_lmt != val_helm_mem_lmt){
    sta_helm_mem_lmt = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (helm_mem_req == val_helm_mem_req){
    sta_helm_mem_req = "OK"
    OK_count++
  }
  else if (helm_mem_req != val_helm_mem_req){
    sta_helm_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (helm_cpu_req == val_helm_cpu_req){
    sta_helm_cpu_req = "OK"
    OK_count++
  }
  else if (helm_cpu_req != val_helm_cpu_req){
    sta_helm_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (prov_def_mem == val_prov_def_mem){
    sta_prov_def_mem = "OK"
    OK_count++
  }
  else if (prov_def_mem != val_prov_def_mem){
    sta_prov_def_mem = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (prov_def_cpu == val_prov_def_cpu){
    sta_prov_def_cpu = "OK"
    OK_count++
  }
  else if (prov_def_cpu != val_prov_def_cpu){
    sta_prov_def_cpu = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (prov_def_type  == val_prov_def_type ){
    sta_prov_def_type  = "OK"
    OK_count++
  }
  else if (prov_def_type  != val_prov_def_type ){
    sta_prov_def_type  = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (prov_crt_mem == val_prov_crt_mem){
    sta_prov_crt_mem = "OK"
    OK_count++
  }
  else if (prov_crt_mem != val_prov_crt_mem){
    sta_prov_crt_mem = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (prov_crt_cpu == val_prov_crt_cpu){
    sta_prov_crt_cpu = "OK"
    OK_count++
  }
  else if (prov_crt_cpu != val_prov_crt_cpu){
    sta_prov_crt_cpu = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (prov_crt_type == val_prov_crt_type){
    sta_prov_crt_type = "OK"
    OK_count++
  }
  else if (prov_crt_type != val_prov_crt_type){
    sta_prov_crt_type = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (pod_count == val_pod_count){
    sta_pod_count = "OK"
    OK_count++
  }
  else if (pod_count != val_pod_count){
    sta_pod_count = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (pod_ready == val_pod_count){
    sta_pod_ready = "OK"
    OK_count++
  }
  else if (pod_ready != val_pod_count){
    sta_pod_ready = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // Print patch value
  echo "mmv2_patch_1: \n${mmv2_patch_1}"
  echo "mmv2_patch_2: \n${mmv2_patch_2}"
  echo "val_mmv2_patch_1: \n${val_mmv2_patch_1}"
  echo "val_mmv2_patch_2: \n${val_mmv2_patch_2}"


  if (mmv2_patch_1.contains("not found")){
    sta_patch = "OK"
    OK_count++
  }
  else {
    if (mmv2_patch_1 == val_mmv2_patch_1){
      if (mmv2_patch_2.contains(val_mmv2_patch_2)){
        sta_patch = "OK"
        OK_count++
      }
      else {
        sta_patch = "NOT OK"
        checkerStatus = "FALSE"
        NOT_OK_count++
      }
    }
    else if (mmv2_patch_1 != val_mmv2_patch_1){
      sta_patch = "NOT OK"
      checkerStatus = "FALSE"
      NOT_OK_count++
    }
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        
        # Add each row with aligned columns
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Helm Version" "${helm_ver}" "${val_helm_ver}" "${sta_helm_ver}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Karpenter Memory Limit" "${helm_mem_lmt}" "${val_helm_mem_lmt}" "${sta_helm_mem_lmt}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Karpenter Memory Request" "${helm_mem_req}" "${val_helm_mem_req}" "${sta_helm_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Karpenter CPU Request" "${helm_cpu_req}" "${val_helm_cpu_req}" "${sta_helm_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Default Provisioner Memory" "${prov_def_mem}" "${val_prov_def_mem}" "${sta_prov_def_mem}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Default Provisioner CPU" "${prov_def_cpu}" "${val_prov_def_cpu}" "${sta_prov_def_cpu}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Default Provisioner Type" "${prov_def_type}" "${val_prov_def_type}" "${sta_prov_def_type}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Critical Provisioner Memory" "${prov_crt_mem}" "${val_prov_crt_mem}" "${sta_prov_crt_mem}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Critical Provisioner CPU" "${prov_crt_cpu}" "${val_prov_crt_cpu}" "${sta_prov_crt_cpu}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Critical Provisioner Type" "${prov_crt_type}" "${val_prov_crt_type}" "${sta_prov_crt_type}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Karpenter Pod Count" "${pod_count}" "${val_pod_count}" "${sta_pod_count}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Karpenter Pod Ready" "${pod_ready}" "${val_pod_count}" "${sta_pod_ready}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Justice Matchmakingv2 Patch" "<not shown>" "<not shown>" "${sta_patch}"
        
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }
  echo "Karpenter OK_COUNT: ${OK_count}"
  echo "Karpenter NOT_OK_COUNT: ${NOT_OK_count}"

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def linkerdChecker(String serviceName){
  def checkerStatus = "TRUE"
  def sta_helm_ver
  def sta_dest_mem_lmt 
  def sta_dest_mem_req
  def sta_dest_cpu_req 
  def sta_helm_nodeselect 
  def sta_pod_count 
  def sta_id_pod_ready
  def sta_dest_pod_ready
  def sta_proxy_pod_ready
  def sta_service_anno
  def sta_patch 
  def OK_count = 0
  def NOT_OK_count = 0

  // Getting cluster info
  def helm_ver = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-control-plane -n linkerd -oyaml | yq '.spec.chart.spec.version'").trim()
  def dest_mem_lmt = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-control-plane -n linkerd -oyaml | yq '.spec.values.destinationResources.memory.limit'").trim()
  def dest_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-control-plane -n linkerd -oyaml | yq '.spec.values.destinationResources.memory.request'").trim()
  def dest_cpu_req  = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-control-plane -n linkerd -oyaml | yq '.spec.values.destinationResources.cpu.request'").trim()
  def helm_nodeselect = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-control-plane -n linkerd -oyaml | yq '.spec.values.nodeSelector'").trim()
  def pod_count = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-control-plane -n linkerd -oyaml | yq '.spec.values.controllerReplicas'").trim()
  def dest_pod_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy linkerd-destination -n linkerd -oyaml | yq -r '.status.readyReplicas'").trim()
  def id_pod_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy linkerd-identity -n linkerd -oyaml | yq -r '.status.readyReplicas'").trim()
  def proxy_pod_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy linkerd-proxy-injector -n linkerd -oyaml | yq -r '.status.readyReplicas'").trim()
  def service_anno = sh(returnStdout: true, quiet: true, script: "kubectl get namespace justice -oyaml | yq -r '.metadata.annotations'").trim()
  def patch = sh(returnStdout: true, quiet: true, script: "kubectl get hr linkerd-viz -n linkerd-viz -oyaml | yq -r '.spec.values.prometheus.persistence'").trim()

  // Separate tierInfo data
  def val_helm_ver = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].helmchart_ver' tempJsonInfo.json """).trim()
  def val_dest_mem_lmt = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].destination_mem_limit' tempJsonInfo.json """).trim()
  def val_dest_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].destination_mem_req' tempJsonInfo.json """).trim()
  def val_dest_cpu_req  = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].destination_cpu_req' tempJsonInfo.json """).trim()
  def val_helm_nodeselect = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].helmrelease_nodeselector' tempJsonInfo.json """).trim()
  def val_pod_count = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].pod_count' tempJsonInfo.json """).trim()
  def val_service_anno = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].justice_service_annotations' tempJsonInfo.json """).trim()
  def val_patch  = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "linkerd") | .data[].custom_patch' tempJsonInfo.json """).trim()

  // Check service annotations
  sta_service_anno = checkEachAnnotations(val_service_anno, service_anno)
  OK_count++

  // Check patch
  sta_patch = checkEachAnnotations(val_patch, patch)
  OK_count++

  // Checking Helm value
  if (sta_service_anno != "OK"){
    checkerStatus = "FALSE"
    OK_count--
    NOT_OK_count++
  }

  if (sta_patch != "OK"){
    checkerStatus = "FALSE"
    OK_count--
    NOT_OK_count++
  }
  
  if (helm_ver == val_helm_ver){
    sta_helm_ver = "OK"
    OK_count++
  }
  else if (helm_ver != val_helm_ver){
    sta_helm_ver = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (dest_mem_lmt == val_dest_mem_lmt){
    sta_dest_mem_lmt = "OK"
    OK_count++
  }
  else if (dest_mem_lmt != val_dest_mem_lmt){
    sta_dest_mem_lmt = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (dest_mem_req == val_dest_mem_req){
    sta_dest_mem_req = "OK"
    OK_count++
  }
  else if (dest_mem_req != val_dest_mem_req){
    sta_dest_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (dest_cpu_req == val_dest_cpu_req){
    sta_dest_cpu_req = "OK"
    OK_count++
  }
  else if (dest_cpu_req != val_dest_cpu_req){
    sta_dest_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (helm_nodeselect.contains(val_helm_nodeselect)){
    sta_helm_nodeselect = "OK"
    OK_count++
  }
  else if (!helm_nodeselect.contains(val_helm_nodeselect)){
    sta_helm_nodeselect = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (pod_count == val_pod_count){
    sta_pod_count = "OK"
    OK_count++
  }
  else if (pod_count != val_pod_count){
    sta_pod_count = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (dest_pod_ready == val_pod_count){
    sta_dest_pod_ready = "OK"
    OK_count++
  }
  else if (dest_pod_ready != val_pod_count){
    sta_dest_pod_ready = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (id_pod_ready == val_pod_count){
    sta_id_pod_ready = "OK"
    OK_count++
  }
  else if (id_pod_ready != val_pod_count){
    sta_id_pod_ready = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (proxy_pod_ready == val_pod_count){
    sta_proxy_pod_ready = "OK"
    OK_count++
  }
  else if (proxy_pod_ready != val_pod_count){
    sta_proxy_pod_ready = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        
        # Add each row with aligned columns
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Helm Version" "${helm_ver}" "${val_helm_ver}" "${sta_helm_ver}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Destination Memory Limit" "${dest_mem_lmt}" "${val_dest_mem_lmt}" "${sta_dest_mem_lmt}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Destination Memory Request" "${dest_mem_req}" "${val_dest_mem_req}" "${sta_dest_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Destination CPU Request" "${dest_cpu_req}" "${val_dest_cpu_req}" "${sta_dest_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Linkerd Pod Count" "${pod_count}" "${val_pod_count}" "${sta_pod_count}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Identity Pod Ready" "${id_pod_ready}" "${val_pod_count}" "${sta_id_pod_ready}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Destination Pod Ready" "${dest_pod_ready}" "${val_pod_count}" "${sta_dest_pod_ready}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Proxy Pod Ready" "${proxy_pod_ready}" "${val_pod_count}" "${sta_proxy_pod_ready}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Justice Service Annotations" "<not shown>" "<not shown>" "${sta_service_anno}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Linkerd CW Nodeselector" "<not shown>" "<not shown>" "${sta_helm_nodeselect}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Linkerd-viz Patch" "<not shown>" "<not shown>" "${sta_patch}"
        
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }
  echo "Linkerd OK_COUNT: ${OK_count}"
  echo "Linkerd NOT_OK_COUNT: ${NOT_OK_count}"

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def emissaryChecker(String serviceName){
  def checkerStatus = "TRUE"
  def OK_count = 0
  def NOT_OK_count = 0
  def sta_image_ver
  def sta_deploy_annotations
  def sta_ambas_mem_req
  def sta_ambas_mem_lmt
  def sta_ambas_cpu_req
  def sta_ambas_lifecycle
  def sta_hpa_cpu_util
  def sta_hpa_mem_utl
  def sta_hpa_min_rep
  def sta_hpa_max_rep
  def sta_termGracePer
  def sta_nodeSelector
  def sta_deploy_affinity
  def sta_pod_ready

  // Getting cluster info
  def image_ver = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .image' | awk -F ':' '{print \$2}'").trim()
  def deploy_annotations = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.metadata.annotations'").trim()
  def ambas_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .resources.requests.memory'").trim()
  def ambas_mem_lmt = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .resources.limits.memory'").trim()
  def ambas_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .resources.requests.cpu'").trim()
  def ambas_lifecycle = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .lifecycle[] | .exec[]'").trim()
  def hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa emissary-ingress -n emissary -oyaml | yq -r '.spec.metrics[] | select(.resource.name == "cpu") | .resource.target.averageUtilization' """).trim()
  def hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa emissary-ingress -n emissary -oyaml | yq -r '.spec.metrics[] | select(.resource.name == "memory") | .resource.target.averageUtilization' """).trim()
  def hpa_min_rep = sh(returnStdout: true, quiet: true, script: "kubectl get hpa emissary-ingress -n emissary -oyaml | yq -r '.spec.minReplicas'").trim()
  def hpa_max_rep = sh(returnStdout: true, quiet: true, script: "kubectl get hpa emissary-ingress -n emissary -oyaml | yq -r '.spec.maxReplicas'").trim()
  def termGracePer = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.terminationGracePeriodSeconds'").trim()
  def nodeSelector = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.nodeSelector'").trim()
  def deploy_affinity = sh(returnStdout: true, quiet: true, script: """ kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[] | .matchExpressions[] | select(.key == "karpenter.k8s.aws/instance-category")| .values' """).trim()
  def pod_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.status.readyReplicas'").trim()
  def pod_avail = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress -n emissary -oyaml | yq -r '.status.availableReplicas'").trim()

  // Separate tierInfo Data
  def val_image_ver = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].image_ver' tempJsonInfo.json """).trim()
  def val_ambas_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].ambassador_mem_req' tempJsonInfo.json """).trim()
  def val_ambas_mem_lmt = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].ambassador_mem_limit' tempJsonInfo.json """).trim()
  def val_ambas_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].ambassador_cpu_req' tempJsonInfo.json """).trim()
  def val_ambas_lifecycle = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].ambassador_lifecycle' tempJsonInfo.json """).trim()
  def val_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].hpa_cpu_util' tempJsonInfo.json """).trim()
  def val_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].hpa_mem_util' tempJsonInfo.json """).trim()
  def val_hpa_min_rep = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].hpa_min_replicas' tempJsonInfo.json """).trim()
  def val_hpa_max_rep = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].hpa_max_replicas' tempJsonInfo.json """).trim()
  def val_termGracePer = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].terminationGracePeriod' tempJsonInfo.json """).trim()
  def val_nodeSelector = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].nodeSelector' tempJsonInfo.json """).trim()
  def val_deploy_affinity = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].nodeAffinity_exclude' tempJsonInfo.json """).trim()
  def val_deploy_annotations = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "emissary") | .data[].deployment_annotations' tempJsonInfo.json """).trim()

  // Separate each annotations
  def parsed_annotations = val_deploy_annotations.split('\n')
  sta_deploy_annotations = "OK"
  echo "val_deploy_annotations: \n${val_deploy_annotations}"
  echo "deploy_annotations: \n${deploy_annotations}"
  OK_count++
  parsed_annotations.eachWithIndex { part, index ->
    if (!deploy_annotations.contains(part)){
      sta_deploy_annotations = "NOT OK"
      echo "FALSE"
      checkerStatus = "FALSE"
      OK_count--
      NOT_OK_count++
    }
  }
  
  // Checking Helm value
  if (image_ver == val_image_ver){
    sta_image_ver = "OK"
    OK_count++
  }
  else if (image_ver != val_image_ver){
    sta_image_ver = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_mem_req == val_ambas_mem_req){
    sta_ambas_mem_req = "OK"
    OK_count++
  }
  else if (ambas_mem_req != val_ambas_mem_req){
    sta_ambas_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_mem_lmt == val_ambas_mem_lmt){
    sta_ambas_mem_lmt = "OK"
    OK_count++
  }
  else if (ambas_mem_lmt != val_ambas_mem_lmt){
    sta_ambas_mem_lmt = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_cpu_req == val_ambas_cpu_req){
    sta_ambas_cpu_req = "OK"
    OK_count++
  }
  else if (ambas_cpu_req != val_ambas_cpu_req){
    sta_ambas_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_lifecycle == val_ambas_lifecycle){
    sta_ambas_lifecycle = "OK"
    OK_count++
  }
  else if (ambas_lifecycle != val_ambas_lifecycle){
    sta_ambas_lifecycle = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_cpu_util == val_hpa_cpu_util){
    sta_hpa_cpu_util = "OK"
    OK_count++
  }
  else if (hpa_cpu_util != val_hpa_cpu_util){
    sta_hpa_cpu_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_mem_util == val_hpa_mem_util){
    sta_hpa_mem_util = "OK"
    OK_count++
  }
  else if (hpa_mem_util != val_hpa_mem_util){
    sta_hpa_mem_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_min_rep == val_hpa_min_rep){
    sta_hpa_min_rep = "OK"
    OK_count++
  }
  else if (hpa_min_rep != val_hpa_min_rep){
    sta_hpa_min_rep = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_max_rep == val_hpa_max_rep){
    sta_hpa_max_rep = "OK"
    OK_count++
  }
  else if (hpa_max_rep != val_hpa_max_rep){
    sta_hpa_max_rep = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (termGracePer == val_termGracePer){
    sta_termGracePer = "OK"
    OK_count++
  }
  else if (termGracePer != val_termGracePer){
    sta_termGracePer = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (nodeSelector.contains(val_nodeSelector)){
    sta_nodeSelector = "OK"
    OK_count++
  }
  else if (!nodeSelector.contains(val_nodeSelector)){
    sta_nodeSelector = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (pod_ready == pod_avail){
    sta_pod_ready = "OK"
    OK_count++
  }
  else if (pod_ready != pod_avail){
    sta_pod_ready = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // Print patch value
  echo "deploy_affinity: \n${deploy_affinity}"
  echo "val_deploy_affinity: \n${val_deploy_affinity}"

  if (deploy_affinity.contains(val_deploy_affinity)){
    sta_deploy_affinity = "OK"
    OK_count++
  }
  else if (!deploy_affinity.contains(val_deploy_affinity)){
    if (deploy_affinity == "" && val_deploy_affinity == "null"){
      sta_deploy_affinity = "OK"
      OK_count++
    }
    else {
      sta_deploy_affinity = "NOT OK"
      checkerStatus = "FALSE"
      NOT_OK_count++
    }
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        
        # Add each row with aligned columns
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Image Version" "${image_ver}" "${val_image_ver}" "${sta_image_ver}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador Memory Limit" "${ambas_mem_lmt}" "${val_ambas_mem_lmt}" "${sta_ambas_mem_lmt}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador Memory Request" "${ambas_mem_req}" "${val_ambas_mem_req}" "${sta_ambas_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador CPU Request" "${ambas_cpu_req}" "${val_ambas_cpu_req}" "${sta_ambas_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador Lifecycle preStop" "<not shown>" "<not shown>" "${sta_ambas_lifecycle}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Deployment Annotations" "<not shown>" "<not shown>" "${sta_deploy_annotations}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA CPU Utilization" "${hpa_cpu_util}" "${val_hpa_cpu_util}" "${sta_hpa_cpu_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Memory Utilization" "${hpa_mem_util}" "${val_hpa_mem_util}" "${sta_hpa_mem_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Min Replicas" "${hpa_min_rep}" "${val_hpa_min_rep}" "${sta_hpa_min_rep}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Max Replicas" "${hpa_max_rep}" "${val_hpa_max_rep}" "${sta_hpa_max_rep}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "terminationGracePeriodSecond" "${termGracePer}" "${val_termGracePer}" "${sta_termGracePer}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Emissary Pod Ready" "${pod_ready}" "${pod_avail}" "${sta_pod_ready}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Emissary CW nodeSelector" "<not shown>" "<not shown>" "${sta_nodeSelector}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Emissary Custom nodeAffinity" "<not shown>" "<not shown>" "${sta_deploy_affinity}"
        
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }
  echo "Emissary OK_COUNT: ${OK_count}"
  echo "Emissary NOT_OK_COUNT: ${NOT_OK_count}"

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def websocketChecker(String serviceName){
  def checkerStatus = "TRUE"
  def OK_count = 0
  def NOT_OK_count = 0
  def skipMessage = ""
  def sta_image_ver
  def sta_deploy_annotations
  def sta_ambas_mem_req
  def sta_ambas_mem_lmt
  def sta_ambas_cpu_req
  def sta_ambas_lifecycle
  def sta_hpa_cpu_util
  def sta_hpa_mem_utl
  def sta_hpa_min_rep
  def sta_hpa_max_rep
  def sta_nodeSelector
  def sta_deploy_affinity
  def sta_pod_ready

  // Getting cluster info
  def image_ver = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .image' | awk -F ':' '{print \$2}'").trim()
  def deploy_annotations = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.metadata.annotations'").trim()
  def ambas_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .resources.requests.memory'").trim()
  def ambas_mem_lmt = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .resources.limits.memory'").trim()
  def ambas_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .resources.requests.cpu'").trim()
  def ambas_lifecycle = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.containers[] | .lifecycle[] | .exec[]'").trim()
  def hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.metrics[] | select(.resource.name == "cpu") | .resource.target.averageUtilization' """).trim()
  def hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.metrics[] | select(.resource.name == "memory") | .resource.target.averageUtilization' """).trim()
  def hpa_min_rep = sh(returnStdout: true, quiet: true, script: "kubectl get hpa emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.minReplicas'").trim()
  def hpa_max_rep = sh(returnStdout: true, quiet: true, script: "kubectl get hpa emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.maxReplicas'").trim()
  def nodeSelector = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.nodeSelector'").trim()
  def deploy_affinity = sh(returnStdout: true, quiet: true, script: """ kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.spec.template.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[] | .matchExpressions[] | select(.key == "karpenter.k8s.aws/instance-category")| .values' """).trim()
  def pod_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.status.readyReplicas'").trim()
  def pod_avail = sh(returnStdout: true, quiet: true, script: "kubectl get deploy emissary-ingress-websocket -n emissary -oyaml | yq -r '.status.availableReplicas'").trim()

  // Separate tierInfo Data
  def val_image_ver = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].image_ver' tempJsonInfo.json """).trim()
  def val_ambas_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].ambassador_mem_req' tempJsonInfo.json """).trim()
  def val_ambas_mem_lmt = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].ambassador_mem_limit' tempJsonInfo.json """).trim()
  def val_ambas_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].ambassador_cpu_req' tempJsonInfo.json """).trim()
  def val_ambas_lifecycle = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].ambassador_lifecycle' tempJsonInfo.json """).trim()
  def val_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].hpa_cpu_util' tempJsonInfo.json """).trim()
  def val_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].hpa_mem_util' tempJsonInfo.json """).trim()
  def val_hpa_min_rep = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].hpa_min_replicas' tempJsonInfo.json """).trim()
  def val_hpa_max_rep = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].hpa_max_replicas' tempJsonInfo.json """).trim()
  def val_nodeSelector = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].nodeSelector' tempJsonInfo.json """).trim()
  def val_deploy_affinity = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].nodeAffinity_exclude' tempJsonInfo.json """).trim()
  def val_deploy_annotations = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "websocket") | .data[].deployment_annotations' tempJsonInfo.json """).trim()

  // Separate each annotations
  def parsed_annotations = val_deploy_annotations.split('\n')
  sta_deploy_annotations = "OK"
  OK_count++
  parsed_annotations.eachWithIndex { part, index ->
    if (!deploy_annotations.contains(part)){
      sta_deploy_annotations = "NOT OK"
      echo "FALSE"
      checkerStatus = "FALSE"
      NOT_OK_count++
      OK_count--
    }
  }
  
  // Checking Helm value
  if (image_ver == val_image_ver){
    sta_image_ver = "OK"
    OK_count++
  }
  else if (image_ver != val_image_ver){
    sta_image_ver = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_mem_req == val_ambas_mem_req){
    sta_ambas_mem_req = "OK"
    OK_count++
  }
  else if (ambas_mem_req != val_ambas_mem_req){
    sta_ambas_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_mem_lmt == val_ambas_mem_lmt){
    sta_ambas_mem_lmt = "OK"
    OK_count++
  }
  else if (ambas_mem_lmt != val_ambas_mem_lmt){
    sta_ambas_mem_lmt = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_cpu_req == val_ambas_cpu_req){
    sta_ambas_cpu_req = "OK"
    OK_count++
  }
  else if (ambas_cpu_req != val_ambas_cpu_req){
    sta_ambas_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (ambas_lifecycle == val_ambas_lifecycle){
    sta_ambas_lifecycle = "OK"
    OK_count++
  }
  else if (ambas_lifecycle != val_ambas_lifecycle){
    sta_ambas_lifecycle = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_cpu_util == val_hpa_cpu_util){
    sta_hpa_cpu_util = "OK"
    OK_count++
  }
  else if (hpa_cpu_util != val_hpa_cpu_util){
    sta_hpa_cpu_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_mem_util == val_hpa_mem_util || hpa_mem_util == null || hpa_mem_util == ""){
    sta_hpa_mem_util = "OK"
    OK_count++
  }
  else if (hpa_mem_util != val_hpa_mem_util){
    sta_hpa_mem_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_min_rep == val_hpa_min_rep){
    sta_hpa_min_rep = "OK"
    OK_count++
  }
  else if (hpa_min_rep != val_hpa_min_rep){
    sta_hpa_min_rep = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (hpa_max_rep == val_hpa_max_rep){
    sta_hpa_max_rep = "OK"
    OK_count++
  }
  else if (hpa_max_rep != val_hpa_max_rep){
    sta_hpa_max_rep = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (nodeSelector.contains(val_nodeSelector)){
    sta_nodeSelector = "OK"
    OK_count++
  }
  else if (!nodeSelector.contains(val_nodeSelector)){
    sta_nodeSelector = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  if (pod_ready == pod_avail){
    sta_pod_ready = "OK"
    OK_count++
  }
  else if (pod_ready != pod_avail){
    sta_pod_ready = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // Print patch value
  echo "deploy_affinity: \n${deploy_affinity}"
  echo "val_deploy_affinity: \n${val_deploy_affinity}"

  if (deploy_affinity.contains(val_deploy_affinity)){
    sta_deploy_affinity = "OK"
    OK_count++
  }
  else if (!deploy_affinity.contains(val_deploy_affinity)){
    if (deploy_affinity == "" && val_deploy_affinity == "null"){
      sta_deploy_affinity = "OK"
      OK_count++
    }
    else {
      sta_deploy_affinity = "NOT OK"
      checkerStatus = "FALSE"
      NOT_OK_count++
    }
  }

  // Update skipped status
  if (!image_ver){
    skipMessage = "| SKIPPED"
    checkerStatus = "SKIP"
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName $skipMessage" 
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        
        # Add each row with aligned columns
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Image Version" "${image_ver}" "${val_image_ver}" "${sta_image_ver}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador Memory Limit" "${ambas_mem_lmt}" "${val_ambas_mem_lmt}" "${sta_ambas_mem_lmt}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador Memory Request" "${ambas_mem_req}" "${val_ambas_mem_req}" "${sta_ambas_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador CPU Request" "${ambas_cpu_req}" "${val_ambas_cpu_req}" "${sta_ambas_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Ambassador Lifecycle preStop" "<not shown>" "<not shown>" "${sta_ambas_lifecycle}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Deployment Annotations" "<not shown>" "<not shown>" "${sta_deploy_annotations}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA CPU Utilization" "${hpa_cpu_util}" "${val_hpa_cpu_util}" "${sta_hpa_cpu_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Memory Utilization" "${hpa_mem_util}" "${val_hpa_mem_util}" "${sta_hpa_mem_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Min Replicas" "${hpa_min_rep}" "${val_hpa_min_rep}" "${sta_hpa_min_rep}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Max Replicas" "${hpa_max_rep}" "${val_hpa_max_rep}" "${sta_hpa_max_rep}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Websocket Pod Ready" "${pod_ready}" "${pod_avail}" "${sta_pod_ready}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Websocket CW nodeSelector" "<not shown>" "<not shown>" "${sta_nodeSelector}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Websocket Custom Affinity" "<not shown>" "<not shown>" "${sta_deploy_affinity}"
        
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }
  echo "Websocket OK_COUNT: ${OK_count}"
  echo "Websocket NOT_OK_COUNT: ${NOT_OK_count}"

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def albChecker(String serviceName){
  def checkerStatus = "TRUE"
  def OK_count = 3
  def NOT_OK_count = 0
  def sta_ing_base_ing
  def sta_ing_em_ing
  def sta_ing_jus_web

  // Getting cluster info
  def ing_base_ing = sh(returnStdout: true, quiet: true, script: "kubectl get ingress -n kube-system base-ingress -oyaml | yq -r '.metadata.annotations'").trim()
  def ing_em_ing = sh(returnStdout: true, quiet: true, script: "kubectl get ingress -n emissary emissary-ingress -oyaml | yq -r '.metadata.annotations'").trim()
  def ing_jus_web = sh(returnStdout: true, quiet: true, script: "kubectl get ingress -n emissary justice-websocket -oyaml | yq -r '.metadata.annotations'").trim()

  // Separate tierInfo Data
  def val_ing_base_ing = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "alb") | .data[].ingress_base_ingress' tempJsonInfo.json """).trim()
  def val_ing_em_ing= sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "alb") | .data[].ingress_emissary_ingress' tempJsonInfo.json """).trim()
  def val_ing_jus_web = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "alb") | .data[].ingress_justice_websocket' tempJsonInfo.json """).trim()

  // Update base ingress value to include env name
  val_ing_base_ing = val_ing_base_ing.replace("ENVIRONMENT_NAME", ENVIRONMENT_NAME)

  // Check each annotations
  sta_ing_base_ing = checkEachAnnotations(val_ing_base_ing, ing_base_ing)
  sta_ing_em_ing = checkEachAnnotations(val_ing_em_ing, ing_em_ing)
  sta_ing_jus_web = checkEachAnnotations(val_ing_jus_web, ing_jus_web)

  // Update checkerStatus for slack msg
  if (sta_ing_base_ing != "OK"){
    checkerStatus = "FALSE"
    OK_count--
    NOT_OK_count++
  }
  if (sta_ing_em_ing != "OK"){
    checkerStatus = "FALSE"
    OK_count--
    NOT_OK_count++
  }
  if (sta_ing_jus_web != "OK"){
    checkerStatus = "FALSE"
    OK_count--
    NOT_OK_count++
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        
        # Add each row with aligned columns
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Base Ingress" "<not shown>" "<not shown>" "${sta_ing_base_ing}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Emissary Ingress" "<not shown>" "<not shown>" "${sta_ing_em_ing}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Justice Websocket" "<not shown>" "<not shown>" "${sta_ing_jus_web}"
        
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }

  echo "ALB OK_COUNT: ${OK_count}"
  echo "ALB NOT_OK_COUNT: ${NOT_OK_count}"

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def otelcollectorChecker(String serviceName){
  def checkerStatus = "TRUE"
  def localServiceName = "otelcollector"
  def OK_count = 0
  def NOT_OK_count = 0
  def localSubServiceName 
  def sta_tar_mem_limit
  def sta_tar_mem_req
  def sta_tar_cpu_req
  def sta_tar_replicas
  def sta_otel_mem_limit
  def sta_otel_mem_req
  def sta_otel_cpu_req
  def sta_otel_hpa_min
  def sta_otel_hpa_max
  def sta_otel_hpa_cpu_util
  def sta_otel_hpa_mem_util
  def sta_logs_mem_limit
  def sta_logs_mem_req
  def sta_logs_cpu_req
  def sta_logs_hpa_min
  def sta_logs_hpa_max
  def sta_logs_hpa_cpu_util
  def sta_logs_hpa_mem_util
  def sta_sts_mem_limit
  def sta_sts_mem_req
  def sta_sts_cpu_req
  def sta_sts_hpa_min
  def sta_sts_hpa_max
  def sta_sts_hpa_cpu_util
  def sta_sts_hpa_mem_util
  def sta_scrap_mem_limit
  def sta_scrap_mem_req
  def sta_scrap_cpu_req
  def sta_scrap_hpa_min
  def sta_scrap_hpa_max

  // Getting cluster info
  // opentelemetry-collector-target-allocator
  localSubServiceName = "opentelemetry-collector-target-allocator"
  def tar_mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.template.spec.containers[].resources.limits.memory'").trim()
  def tar_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.template.spec.containers[].resources.requests.memory'").trim()
  def tar_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.template.spec.containers[].resources.requests.cpu'").trim()
  def tar_replicas = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.status.readyReplicas'").trim()
  def val_tar_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_limit' tempJsonInfo.json """).trim()
  def val_tar_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_request' tempJsonInfo.json """).trim()
  def val_tar_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .cpu_request' tempJsonInfo.json """).trim()
  def val_tar_replicas = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .replicas' tempJsonInfo.json """).trim()

  // opentelemetry-collector
  localSubServiceName = "opentelemetry-collector"
  def otel_mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.limits.memory'").trim()
  def otel_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.memory'").trim()
  def otel_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.cpu'").trim()
  def otel_hpa_min = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.minReplicas'").trim()
  def otel_hpa_max = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.maxReplicas'").trim()
  def otel_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.metrics[].resource | select(.name == "cpu") | .target.averageUtilization' """).trim()
  def otel_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.metrics[].resource | select(.name == "memory") | .target.averageUtilization' """).trim()
  def val_otel_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_limit' tempJsonInfo.json """).trim()
  def val_otel_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_request' tempJsonInfo.json """).trim()
  def val_otel_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .cpu_request' tempJsonInfo.json """).trim()
  def val_otel_hpa_min = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_min_rep' tempJsonInfo.json """).trim()
  def val_otel_hpa_max = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_max_rep' tempJsonInfo.json """).trim()
  def val_otel_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_cpu_util' tempJsonInfo.json """).trim()
  def val_otel_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_mem_util' tempJsonInfo.json """).trim()

  // opentelemetry-collector-deployment-logs
  localSubServiceName = "opentelemetry-collector-deployment-logs"
  def logs_mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.limits.memory'").trim()
  def logs_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.memory'").trim()
  def logs_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.cpu'").trim()
  def logs_hpa_min = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.minReplicas'").trim()
  def logs_hpa_max = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.maxReplicas'").trim()
  def logs_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.metrics[].resource | select(.name == "cpu") | .target.averageUtilization' """).trim()
  def logs_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.metrics[].resource | select(.name == "memory") | .target.averageUtilization' """).trim()
  def val_logs_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_limit' tempJsonInfo.json """).trim()
  def val_logs_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_request' tempJsonInfo.json """).trim()
  def val_logs_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .cpu_request' tempJsonInfo.json """).trim()
  def val_logs_hpa_min = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_min_rep' tempJsonInfo.json """).trim()
  def val_logs_hpa_max = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_max_rep' tempJsonInfo.json """).trim()
  def val_logs_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_cpu_util' tempJsonInfo.json """).trim()
  def val_logs_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_mem_util' tempJsonInfo.json """).trim()

  // opentelemetry-collector-sts
  localSubServiceName = "opentelemetry-collector-sts"
  def sts_mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.limits.memory'").trim()
  def sts_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.memory'").trim()
  def sts_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.cpu'").trim()
  def sts_hpa_min = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.minReplicas'").trim()
  def sts_hpa_max = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.maxReplicas'").trim()
  def sts_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.metrics[].resource | select(.name == "cpu") | .target.averageUtilization' """).trim()
  def sts_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.metrics[].resource | select(.name == "memory") | .target.averageUtilization' """).trim()
  def val_sts_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_limit' tempJsonInfo.json """).trim()
  def val_sts_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_request' tempJsonInfo.json """).trim()
  def val_sts_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .cpu_request' tempJsonInfo.json """).trim()
  def val_sts_hpa_min = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_min_rep' tempJsonInfo.json """).trim()
  def val_sts_hpa_max = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_max_rep' tempJsonInfo.json """).trim()
  def val_sts_hpa_cpu_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_cpu_util' tempJsonInfo.json """).trim()
  def val_sts_hpa_mem_util = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_mem_util' tempJsonInfo.json """).trim()

  // opentelemetry-collector-sts-metrics-scraper
  localSubServiceName = "opentelemetry-collector-sts-metrics-scraper"
  def scrap_mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.limits.memory'").trim()
  def scrap_mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.memory'").trim()
  def scrap_cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.values.resources.requests.cpu'").trim()
  def scrap_hpa_min = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.minReplicas'").trim()
  def scrap_hpa_max = sh(returnStdout: true, quiet: true, script: "kubectl get hpa ${localSubServiceName} -n ${localServiceName} -oyaml | yq -r '.spec.maxReplicas'").trim()
  def val_scrap_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_limit' tempJsonInfo.json """).trim()
  def val_scrap_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .mem_request' tempJsonInfo.json """).trim()
  def val_scrap_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .cpu_request' tempJsonInfo.json """).trim()
  def val_scrap_hpa_min = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_min_rep' tempJsonInfo.json """).trim()
  def val_scrap_hpa_max = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "${localServiceName}").data[] | select(.service == "${localSubServiceName}") | .hpa_max_rep' tempJsonInfo.json """).trim()

  // Update checkerStatus for slack msg
  // opentelemetry-collector-target-allocator
  if (tar_mem_limit == val_tar_mem_limit){
    sta_tar_mem_limit = "OK"
    OK_count++
  }
  else if (tar_mem_limit != val_tar_mem_limit){
    sta_tar_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (tar_mem_req == val_tar_mem_req){
    sta_tar_mem_req = "OK"
    OK_count++
  }
  else if (tar_mem_req != val_tar_mem_req){
    sta_tar_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (tar_cpu_req == val_tar_cpu_req){
    sta_tar_cpu_req = "OK"
    OK_count++
  }
  else if (tar_cpu_req != val_tar_cpu_req){
    sta_tar_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (tar_replicas == val_tar_replicas){
    sta_tar_replicas = "OK"
    OK_count++
  }
  else if (tar_replicas != val_tar_replicas){
    sta_tar_replicas = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // opentelemetry-collector
  if (otel_mem_limit == val_otel_mem_limit){
    sta_otel_mem_limit = "OK"
    OK_count++
  }
  else if (otel_mem_limit != val_otel_mem_limit){
    sta_otel_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (otel_mem_req == val_otel_mem_req){
    sta_otel_mem_req = "OK"
    OK_count++
  }
  else if (otel_mem_req != val_otel_mem_req){
    sta_otel_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (otel_cpu_req == val_otel_cpu_req){
    sta_otel_cpu_req = "OK"
    OK_count++
  }
  else if (otel_cpu_req != val_otel_cpu_req){
    sta_otel_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (otel_hpa_min == val_otel_hpa_min){
    sta_otel_hpa_min = "OK"
    OK_count++
  }
  else if (otel_hpa_min != val_otel_hpa_min){
    sta_otel_hpa_min = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (otel_hpa_max == val_otel_hpa_max){
    sta_otel_hpa_max = "OK"
    OK_count++
  }
  else if (otel_hpa_max != val_otel_hpa_max){
    sta_otel_hpa_max = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (otel_hpa_cpu_util == val_otel_hpa_cpu_util){
    sta_otel_hpa_cpu_util= "OK"
    OK_count++
  }
  else if (otel_hpa_cpu_util != val_otel_hpa_cpu_util){
    sta_otel_hpa_cpu_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (otel_hpa_mem_util == val_otel_hpa_mem_util){
    sta_otel_hpa_mem_util= "OK"
    OK_count++
  }
  else if (otel_hpa_mem_util != val_otel_hpa_mem_util){
    sta_otel_hpa_mem_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // opentelemetry-collector-deployment-logs
  if (logs_mem_limit == val_logs_mem_limit){
    sta_logs_mem_limit = "OK"
    OK_count++
  }
  else if (logs_mem_limit != val_logs_mem_limit){
    sta_logs_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (logs_mem_req == val_logs_mem_req){
    sta_logs_mem_req = "OK"
    OK_count++
  }
  else if (logs_mem_req != val_logs_mem_req){
    sta_logs_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (logs_cpu_req == val_logs_cpu_req){
    sta_logs_cpu_req = "OK"
    OK_count++
  }
  else if (logs_cpu_req != val_logs_cpu_req){
    sta_logs_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (logs_hpa_min == val_logs_hpa_min){
    sta_logs_hpa_min = "OK"
    OK_count++
  }
  else if (logs_hpa_min != val_logs_hpa_min){
    sta_logs_hpa_min = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (logs_hpa_max == val_logs_hpa_max){
    sta_logs_hpa_max = "OK"
    OK_count++
  }
  else if (logs_hpa_max != val_logs_hpa_max){
    sta_logs_hpa_max = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (logs_hpa_cpu_util == val_logs_hpa_cpu_util){
    sta_logs_hpa_cpu_util= "OK"
    OK_count++
  }
  else if (logs_hpa_cpu_util != val_logs_hpa_cpu_util){
    sta_logs_hpa_cpu_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (logs_hpa_mem_util == val_logs_hpa_mem_util){
    sta_logs_hpa_mem_util= "OK"
    OK_count++
  }
  else if (logs_hpa_mem_util != val_logs_hpa_mem_util){
    sta_logs_hpa_mem_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // opentelemetry-collector-sts
  if (sts_mem_limit == val_sts_mem_limit){
    sta_sts_mem_limit = "OK"
    OK_count++
  }
  else if (sts_mem_limit != val_sts_mem_limit){
    sta_sts_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (sts_mem_req == val_sts_mem_req){
    sta_sts_mem_req = "OK"
    OK_count++
  }
  else if (sts_mem_req != val_sts_mem_req){
    sta_sts_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (sts_cpu_req == val_sts_cpu_req){
    sta_sts_cpu_req = "OK"
    OK_count++
  }
  else if (sts_cpu_req != val_sts_cpu_req){
    sta_sts_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (sts_hpa_min == val_sts_hpa_min){
    sta_sts_hpa_min = "OK"
    OK_count++
  }
  else if (sts_hpa_min != val_sts_hpa_min){
    sta_sts_hpa_min = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (sts_hpa_max == val_sts_hpa_max){
    sta_sts_hpa_max = "OK"
    OK_count++
  }
  else if (sts_hpa_max != val_sts_hpa_max){
    sta_sts_hpa_max = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (sts_hpa_cpu_util == val_sts_hpa_cpu_util){
    sta_sts_hpa_cpu_util= "OK"
    OK_count++
  }
  else if (sts_hpa_cpu_util != val_sts_hpa_cpu_util){
    sta_sts_hpa_cpu_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (sts_hpa_mem_util == val_sts_hpa_mem_util){
    sta_sts_hpa_mem_util= "OK"
    OK_count++
  }
  else if (sts_hpa_mem_util != val_sts_hpa_mem_util){
    sta_sts_hpa_mem_util = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // opentelemetry-collector-sts-metrics-scraper
  if (scrap_mem_limit == val_scrap_mem_limit){
    sta_scrap_mem_limit = "OK"
    OK_count++
  }
  else if (scrap_mem_limit != val_scrap_mem_limit){
    sta_scrap_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (scrap_mem_req == val_scrap_mem_req){
    sta_scrap_mem_req = "OK"
    OK_count++
  }
  else if (scrap_mem_req != val_scrap_mem_req){
    sta_scrap_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (scrap_cpu_req == val_scrap_cpu_req){
    sta_scrap_cpu_req = "OK"
    OK_count++
  }
  else if (scrap_cpu_req != val_scrap_cpu_req){
    sta_scrap_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (scrap_hpa_min == val_scrap_hpa_min){
    sta_scrap_hpa_min = "OK"
    OK_count++
  }
  else if (scrap_hpa_min != val_scrap_hpa_min){
    sta_scrap_hpa_min = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (scrap_hpa_max == val_scrap_hpa_max){
    sta_scrap_hpa_max = "OK"
    OK_count++
  }
  else if (scrap_hpa_max != val_scrap_hpa_max){
    sta_scrap_hpa_max = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }


  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+-----------------------------------------------------------------------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-69s |\n" opentelemetry-collector-target-allocator
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${tar_mem_limit}" "${val_tar_mem_limit}" "${sta_tar_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${tar_mem_req}" "${val_tar_mem_req}" "${sta_tar_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${tar_cpu_req}" "${val_tar_cpu_req}" "${sta_tar_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Replicas" "${tar_replicas}" "${val_tar_replicas}" "${sta_tar_replicas}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-69s |\n" opentelemetry-collector
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${otel_mem_limit}" "${val_otel_mem_limit}" "${sta_otel_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${otel_mem_req}" "${val_otel_mem_req}" "${sta_otel_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${otel_cpu_req}" "${val_otel_cpu_req}" "${sta_otel_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Min Replicas" "${otel_hpa_min}" "${val_otel_hpa_min}" "${sta_otel_hpa_min}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Max Replicas" "${otel_hpa_max}" "${val_otel_hpa_max}" "${sta_otel_hpa_max}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA CPU Utils" "${otel_hpa_cpu_util}" "${val_otel_hpa_cpu_util}" "${sta_otel_hpa_cpu_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Memory Utils" "${otel_hpa_mem_util}" "${val_otel_hpa_mem_util}" "${sta_otel_hpa_mem_util}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-69s |\n" opentelemetry-collector-deployment-logs
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${logs_mem_limit}" "${val_logs_mem_limit}" "${sta_logs_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${logs_mem_req}" "${val_logs_mem_req}" "${sta_logs_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${logs_cpu_req}" "${val_logs_cpu_req}" "${sta_logs_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Min Replicas" "${logs_hpa_min}" "${val_logs_hpa_min}" "${sta_logs_hpa_min}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Max Replicas" "${logs_hpa_max}" "${val_logs_hpa_max}" "${sta_logs_hpa_max}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA CPU Utils" "${logs_hpa_cpu_util}" "${val_logs_hpa_cpu_util}" "${sta_logs_hpa_cpu_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Memory Utils" "${logs_hpa_mem_util}" "${val_logs_hpa_mem_util}" "${sta_logs_hpa_mem_util}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-69s |\n" opentelemetry-collector-sts
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${sts_mem_limit}" "${val_sts_mem_limit}" "${sta_sts_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${sts_mem_req}" "${val_sts_mem_req}" "${sta_sts_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${sts_cpu_req}" "${val_sts_cpu_req}" "${sta_sts_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Min Replicas" "${sts_hpa_min}" "${val_sts_hpa_min}" "${sta_sts_hpa_min}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Max Replicas" "${sts_hpa_max}" "${val_sts_hpa_max}" "${sta_sts_hpa_max}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA CPU Utils" "${sts_hpa_cpu_util}" "${val_sts_hpa_cpu_util}" "${sta_sts_hpa_cpu_util}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Memory Utils" "${sts_hpa_mem_util}" "${val_sts_hpa_mem_util}" "${sta_sts_hpa_mem_util}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-69s |\n" opentelemetry-collector-sts-metrics-scraper
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${scrap_mem_limit}" "${val_scrap_mem_limit}" "${sta_scrap_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${scrap_mem_req}" "${val_scrap_mem_req}" "${sta_scrap_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${scrap_cpu_req}" "${val_scrap_cpu_req}" "${sta_scrap_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Min Replicas" "${scrap_hpa_min}" "${val_scrap_hpa_min}" "${sta_scrap_hpa_min}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "HPA Max Replicas" "${scrap_hpa_max}" "${val_scrap_hpa_max}" "${sta_scrap_hpa_max}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def fluentdChecker(String serviceName){
  def checkerStatus = "TRUE"
  def OK_count = 0
  def NOT_OK_count = 0
  def sta_mem_limit
  def sta_mem_req
  def sta_cpu_limit
  def sta_cpu_req
  def sta_pod

  // Getting cluster info
  def mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get daemonset -n logging logging-fluentd -oyaml | yq -r '.spec.template.spec.containers[].resources.limits.memory'").trim()
  def mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get daemonset -n logging logging-fluentd -oyaml | yq -r '.spec.template.spec.containers[].resources.requests.memory'").trim()
  def cpu_limit = sh(returnStdout: true, quiet: true, script: "kubectl get daemonset -n logging logging-fluentd -oyaml | yq -r '.spec.template.spec.containers[].resources.limits.cpu'").trim()
  def cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get daemonset -n logging logging-fluentd -oyaml | yq -r '.spec.template.spec.containers[].resources.requests.cpu'").trim()
  def desired_pod = sh(returnStdout: true, quiet: true, script: "kubectl get daemonset -n logging logging-fluentd -oyaml | yq -r '.status.desiredNumberScheduled'").trim()
  def ready_pod = sh(returnStdout: true, quiet: true, script: "kubectl get daemonset -n logging logging-fluentd -oyaml | yq -r '.status.numberReady'").trim()

  // Separate tierInfo Data
  def val_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "fluentd") | .data[].mem_limit' tempJsonInfo.json """).trim()
  def val_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "fluentd") | .data[].mem_request' tempJsonInfo.json """).trim()
  def val_cpu_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "fluentd") | .data[].cpu_limit' tempJsonInfo.json """).trim()
  def val_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "fluentd") | .data[].cpu_request' tempJsonInfo.json """).trim()

  // Update checkerStatus for slack msg
  if (mem_limit == val_mem_limit){
    sta_mem_limit = "OK"
    OK_count++
  }
  else if (mem_limit != val_mem_limit){
    sta_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (mem_req == val_mem_req){
    sta_mem_req = "OK"
    OK_count++
  }
  else if (mem_req != val_mem_req){
    sta_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (cpu_limit == val_cpu_limit){
    sta_cpu_limit = "OK"
    OK_count++
  }
  else if (cpu_limit != val_cpu_limit){
    sta_cpu_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (cpu_req == val_cpu_req){
    sta_cpu_req = "OK"
    OK_count++
  }
  else if (cpu_req != val_cpu_req){
    sta_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (desired_pod == ready_pod){
    sta_pod = "OK"
    OK_count++
  }
  else if (desired_pod != ready_pod){
    sta_pod = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${mem_limit}" "${val_mem_limit}" "${sta_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${mem_req}" "${val_mem_req}" "${sta_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Limit" "${cpu_limit}" "${val_cpu_limit}" "${sta_cpu_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${cpu_req}" "${val_cpu_req}" "${sta_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Daemonset Pod Status" "${ready_pod}" "${desired_pod}" "${sta_pod}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}

def ksmChecker(String serviceName){
  def checkerStatus = "TRUE"
  def OK_count = 0
  def NOT_OK_count = 0
  def sta_mem_limit
  def sta_mem_req
  def sta_cpu_limit
  def sta_cpu_req
  def sta_replicas
  def sta_autosharding
  def sta_pod

  // Getting cluster info
  def mem_limit = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease -n monitoring kube-state-metrics -oyaml | yq -r '.spec.values.resources.limits.memory'").trim()
  def mem_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease -n monitoring kube-state-metrics -oyaml | yq -r '.spec.values.resources.requests.memory'").trim()
  def cpu_limit = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease -n monitoring kube-state-metrics -oyaml | yq -r '.spec.values.resources.limits.cpu'").trim()
  def cpu_req = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease -n monitoring kube-state-metrics -oyaml | yq -r '.spec.values.resources.requests.cpu'").trim()
  def replicas = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease -n monitoring kube-state-metrics -oyaml | yq -r '.spec.values.replicas'").trim() 
  def autosharding = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease -n monitoring kube-state-metrics -oyaml | yq -r '.spec.values.autosharding.enabled'").trim()
  def available_pod = sh(returnStdout: true, quiet: true, script: "kubectl get statefulset kube-state-metrics -n monitoring -oyaml | yq -r '.status.availableReplicas'").trim()
  def ready_pod = sh(returnStdout: true, quiet: true, script: "kubectl get statefulset kube-state-metrics -n monitoring -oyaml | yq -r '.status.readyReplicas'").trim()

  // Separate tierInfo Data
  def val_mem_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "ksm") | .data[].mem_limit' tempJsonInfo.json """).trim()
  def val_mem_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "ksm") | .data[].mem_request' tempJsonInfo.json """).trim()
  def val_cpu_limit = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "ksm") | .data[].cpu_limit' tempJsonInfo.json """).trim()
  def val_cpu_req = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "ksm") | .data[].cpu_request' tempJsonInfo.json """).trim()
  def val_replicas = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "ksm") | .data[].replicas' tempJsonInfo.json """).trim()
  def val_autosharding = sh(returnStdout: true, quiet: true, script: """ jq -r '.[] | select(.service == "ksm") | .data[].autosharding_enabled' tempJsonInfo.json """).trim()

  // Update checkerStatus for slack msg
  if (mem_limit == val_mem_limit){
    sta_mem_limit = "OK"
    OK_count++
  }
  else if (mem_limit != val_mem_limit){
    sta_mem_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (mem_req == val_mem_req){
    sta_mem_req = "OK"
    OK_count++
  }
  else if (mem_req != val_mem_req){
    sta_mem_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (cpu_limit == val_cpu_limit){
    sta_cpu_limit = "OK"
    OK_count++
  }
  else if (cpu_limit != val_cpu_limit){
    sta_cpu_limit = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (cpu_req == val_cpu_req){
    sta_cpu_req = "OK"
    OK_count++
  }
  else if (cpu_req != val_cpu_req){
    sta_cpu_req = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (replicas == val_replicas){
    sta_replicas = "OK"
    OK_count++
  }
  else if (replicas != val_replicas){
    sta_replicas = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (autosharding == val_autosharding){
    sta_autosharding = "OK"
    OK_count++
  }
  else if (autosharding != val_autosharding){
    sta_autosharding = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }
  if (available_pod == ready_pod){
    sta_pod = "OK"
    OK_count++
  }
  else if (available_pod != ready_pod){
    sta_pod = "NOT OK"
    checkerStatus = "FALSE"
    NOT_OK_count++
  }

  // Create table report
  dir("report") {
    sh """
      #!/bin/bash
      echo "" >> diffTableReport.txt
      {
        printf ""
        printf "+-----------------------------------------------------------------------+\n"
        printf "| Service Name: %-55s |\n" "$serviceName"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Data Name" "Actual" "Desired" "Status"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Limit" "${mem_limit}" "${val_mem_limit}" "${sta_mem_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Memory Request" "${mem_req}" "${val_mem_req}" "${sta_mem_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Limit" "${cpu_limit}" "${val_cpu_limit}" "${sta_cpu_limit}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "CPU Request" "${cpu_req}" "${val_cpu_req}" "${sta_cpu_req}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Replicas" "${replicas}" "${val_replicas}" "${sta_replicas}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Autosharding Enabled" "${autosharding}" "${val_autosharding}" "${sta_autosharding}"
        printf "| %-28s | %-13s | %-13s | %-6s |\n" "Daemonset Pod Status" "${ready_pod}" "${available_pod}" "${sta_pod}"
        printf "+------------------------------+---------------+---------------+--------+\n"
        printf ""
      } >> diffTableReport.txt

      echo "" >> diffTableReport.txt
    """
  }

  return [checkerStatus: checkerStatus, OK_count: OK_count, NOT_OK_count: NOT_OK_count]
}


def checkEachAnnotations(String inputAnnotations, targetAnnotations){
  def status = "OK"
  if(targetAnnotations == null || targetAnnotations == "null"){
    status = "OK"
  }
  else{
    // Separate each annotations
    echo "inputAnnotations: \n${inputAnnotations}"
    echo "targetAnnotations: \n${targetAnnotations}"
    def parsed_annotations = inputAnnotations.split('\n')
    parsed_annotations.eachWithIndex { part, index ->
      if (!targetAnnotations.contains(part)){
        status = "NOT OK"
      }
    }
  }
  return status
}

void generateSlackMessage(String message, slackThread, slackToken){
  // POST
  def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
  def postData =  [
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
                text: "${message}"
              ]
            ]
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
      def jsonSlurper = new JsonSlurper()
      def reply = post.getInputStream().getText()
      def replyMap = jsonSlurper.parseText(reply)
  }
}

def initMessage(String message, slackThread, slackToken){
  // POST
  def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
  def postData =  [
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
                text: "${message} Check "
              ],
              [
                type: "emoji",
                name: "loading2"
              ]
            ]
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
      def jsonSlurper = new JsonSlurper()
      def reply = post.getInputStream().getText()
      def replyMap = jsonSlurper.parseText(reply)
      return replyMap.message.ts
  }
}

def updateMessage(String message, slackMessage, slackToken, checkerStatus){
  def slackEmoji
  def statusMsg

  switch(checkerStatus) {
    case "TRUE":
      slackEmoji = "checkmark"
      statusMsg = "Done"
      break
    case "FALSE":
      slackEmoji = "x"
      statusMsg = "Failed"
      break
    case "SKIP":
      slackEmoji = "white_circle"
      statusMsg = "Skipped"
    default:
      throw new Exception("Unknown Jenkins build status: $status")
  }

  // POST
  def post = new URL("https://slack.com/api/chat.update").openConnection();
  def postData =  [
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
                text: "${message} ${statusMsg} "
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
    ts: "${slackMessage}"
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
  }
}

def pushReport(slackThread, slackToken){
  dir("tierTemp${BUILD_NUMBER}") {
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
  def postData =  [
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
                text: "[REPORT]",
                style: [
                  bold: true
                ]
              ]
            ]
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
    def jsonSlurper = new JsonSlurper()
    def reply = post.getInputStream().getText()
    def replyMap = jsonSlurper.parseText(reply)
  }
}

void createBanner(String message) {
  ansiColor('xterm'){
    echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    echo '\033[1;4;33m' + ":: ${message}"
    echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
  }
}

def calculatePercentage(part, total) {
    if (total == 0) {
        return "Undefined (division by zero)"
    }
    def percentage = (part / total) * 100
    return String.format("%.2f", percentage)
}