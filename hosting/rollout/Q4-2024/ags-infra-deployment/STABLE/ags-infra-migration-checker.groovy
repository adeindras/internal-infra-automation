import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
  [
    parameters([
	  choice(choices: envList, description: "Environment to migrate", name: "targetEnvironmentName"),
      choice(choices: ["pre-check", "post-check"].join("\n"), name: 'checkCondition', description: 'Pre / Post Check'),
      choice(choices: ["true", "false"].join("\n"), name: 'includeObvCheck', description: 'Include Obv Functionality Check')
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

// constants
ENVIRONMENT_NAME = params.targetEnvironmentName
CHECK_CONDITION = params.checkCondition
OBV_FUNCTIONALITY = params.includeObvCheck
BITBUCKET_CREDS_ID = 'bitbucket-repo-read-only'
DEPLOYMENT_REPO_SLUG = "deployments"

String targetEnvironmentName = params.targetEnvironmentName
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
def prHtmlLink
def slackChannel
def buildStopped = false
currentBuild.displayName = "#${BUILD_NUMBER}-infra-migration-checker-${targetEnvironmentName}"
def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
def WORKSPACE_STATE
def ENV_PATH = ENVIRONMENT_NAME.replace('-','/')

node('deploy-agent') {
	container('tool') {
        stage('Pipeline Pre-Check'){
            createBanner("STAGE: Pipeline Pre-Check")
            if (ENVIRONMENT_NAME == '' || ENVIRONMENT_NAME == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (CHECK_CONDITION == '' || CHECK_CONDITION == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            coloredOutput("Environment Name", ENVIRONMENT_NAME)
            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07UY55SE20"
				WORKSPACE_STATE = "DEVELOPMENT"
            } else {
                slackChannel = "C080SRE92NA"
				WORKSPACE_STATE = "STABLE"
            }
        }
        if (!buildStopped) {
            stage('Initialization') {
                createBanner("STAGE: Initializing..")
                currentBuild.displayName = "#${BUILD_NUMBER} - ${CHECK_CONDITION} - ${ENVIRONMENT_NAME}"
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
            if (CHECK_CONDITION == "pre-check"){
                stage("Checking") {
                    createBanner("STAGE: Validating ${CHECK_CONDITION}")
                    dir("tierTemp${BUILD_NUMBER}") {
                        podChecker()
                        hrChecker(CHECK_CONDITION)
                        pushReport()
                    }
                }
            }
            if (CHECK_CONDITION == "post-check"){
                stage("Checking") {
                    createBanner("STAGE: Validating ${CHECK_CONDITION}")
                    dir("tierTemp${BUILD_NUMBER}") {
                        podChecker()
                        hrChecker(CHECK_CONDITION)
                        pushReport()
                    }
                }
            }
            stage('Sending Notification'){
                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: slackChannel,
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: ":small_red_triangle: MIGRATION CHECKER :small_red_triangle: \n*<${BUILD_URL}console|Go to Jenkins now!>*"
                                ]
                            ], 
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*Cluster:*\n${ENVIRONMENT_NAME}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Check Type:*\n${CHECK_CONDITION}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Triggered by:*\n${userId}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Result:*\n<${BUILD_URL}Diff_20Report/|REPORT>"
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
            if (OBV_FUNCTIONALITY == "true"){
                stage('Obv Function Check') {
                    build job: 'obv-tools-checker',
                    parameters: [
                        [$class: 'StringParameterValue', name: 'targetEnvironmentName', value: ENVIRONMENT_NAME],
                        [$class: 'StringParameterValue', name: 'BUILD_TRIGGER_BY', value: userId]
                    ],
                    wait: false
                }
            }
        }
    }
}

def podChecker(){
    // Check helm-controller
    def deployToCheck = "helm-controller"
    def helm_con_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.spec.replicas'").trim()
    def helm_con_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.status.availableReplicas'").trim()
    def helm_con_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n flux-system").trim()

    // Check image-automation-controller
    deployToCheck = "image-automation-controller"
    def img_auto_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.spec.replicas'").trim()
    def img_auto_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.status.availableReplicas'").trim()
    def img_auto_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n flux-system").trim()

    // Check image-reflector-controller
    deployToCheck = "image-reflector-controller"
    def img_ref_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.spec.replicas'").trim()
    def img_ref_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.status.availableReplicas'").trim()
    def img_ref_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n flux-system").trim()

    // Check kustomize-controller
    deployToCheck = "kustomize-controller"
    def kus_con_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.spec.replicas'").trim()
    def kus_con_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.status.availableReplicas'").trim()
    def kus_con_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n flux-system").trim()

    // Check notification-controller
    deployToCheck = "notification-controller"
    def not_con_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.spec.replicas'").trim()
    def not_con_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.status.availableReplicas'").trim()
    def not_con_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n flux-system").trim()

    // Check source-controller
    deployToCheck = "source-controller"
    def src_con_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.spec.replicas'").trim()
    def src_con_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n flux-system -oyaml | yq '.status.availableReplicas'").trim()
    def src_con_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n flux-system").trim()

    // Check karpenter
    deployToCheck = "karpenter"
    def kar_ready = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n karpenter -oyaml | yq '.spec.replicas'").trim()
    def kar_available = sh(returnStdout: true, quiet: true, script: "kubectl get deploy ${deployToCheck} -n karpenter -oyaml | yq '.status.availableReplicas'").trim()
    def kar_status = sh(returnStdout: true, quiet: true, script: "kubectl rollout status deploy ${deployToCheck} -n karpenter").trim()

    // Create table report
    dir("report") {
    sh """
        #!/bin/bash
        echo "" >> diffTableReport.txt
        {
        printf ""
        printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
        printf "| Core Infra Deployment Checker Result                                                                                                                           |\n"
        printf "+------------------------------+---------------+---------------+-------------------------------------------------------------------------------------------------+\n"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "Data Name" "Ready Pod" "Available Pod" "Deploy Status"
        printf "+------------------------------+---------------+---------------+-------------------------------------------------------------------------------------------------+\n"
        
        # Add each row with aligned columns
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "helm-controller" "${helm_con_ready}" "${helm_con_available}" "${helm_con_status}"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "image-automation-controller" "${img_auto_ready}" "${img_auto_available}" "${img_auto_status}"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "image-reflector-controller" "${img_ref_ready}" "${img_ref_available}" "${img_ref_status}"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "kustomize-controller" "${kus_con_ready}" "${kus_con_available}" "${kus_con_status}"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "notification-controller" "${not_con_ready}" "${not_con_available}" "${not_con_status}"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "source-controller" "${src_con_ready}" "${src_con_available}" "${src_con_status}"
        printf "| %-28s | %-13s | %-13s | %-95s |\n" "karpenter" "${kar_ready}" "${kar_available}" "${kar_status}"
        
        printf "+------------------------------+---------------+---------------+-------------------------------------------------------------------------------------------------+\n"
        printf ""
        } >> diffTableReport.txt

        echo "" >> diffTableReport.txt
    """
    }
}

def hrChecker(String condition){
    if (condition == "pre-check"){
        // Check karpenter
        def helmToCheck = "karpenter"
        def hr_kar_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n karpenter -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_kar_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n karpenter -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check linkerd-control-plane
        helmToCheck = "linkerd-control-plane"
        def hr_lincp_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_lincp_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check linkerd-crds
        helmToCheck = "linkerd-crds"
        def hr_lincr_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_lincr_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check prometheus-operator
        helmToCheck = "prometheus-operator"
        def hr_prom_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n monitoring -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_prom_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n monitoring -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector
        helmToCheck = "opentelemetry-collector"
        def hr_oc_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_oc_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-sts
        helmToCheck = "opentelemetry-collector-sts"
        def hr_ocs_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocs_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-deployment-logs
        helmToCheck = "opentelemetry-collector-deployment-logs"
        def hr_ocdl_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocdl_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-sts-metrics-scraper
        helmToCheck = "opentelemetry-collector-sts-metrics-scraper"
        def hr_ocsms_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocsms_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()
    
        // Check opentelemetry-collector-sts-metrics-scraper-lobby
        helmToCheck = "opentelemetry-collector-sts-metrics-scraper-lobby"
        def hr_ocsmsl_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocsmsl_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-sts-watcher
        helmToCheck = "opentelemetry-collector-sts-watcher"
        def hr_ocsw_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocsw_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Create table report
        dir("report") {
        sh """
            #!/bin/bash
            echo "" >> diffTableReport.txt
            {
            printf ""
            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf "| Helmrelease Checker Result                                                                                                                                     |\n"
            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "Helmrelease" "Namespace" "Message" "Status"
            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            
            # Add each row with aligned columns
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "karpenter" "karpenter" "${hr_kar_msg}" "${hr_kar_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "linkerd-control-plane" "linkerd" "${hr_lincp_msg}" "${hr_lincp_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "linkerd-crds" "linkerd" "${hr_lincr_msg}" "${hr_lincr_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "prometheus-operator" "monitoring" "${hr_prom_msg}" "${hr_prom_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector" "otelcollector" "${hr_oc_msg}" "${hr_oc_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts" "otelcollector" "${hr_ocs_msg}" "${hr_ocs_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-deployment-logs" "otelcollector" "${hr_ocdl_msg}" "${hr_ocdl_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts-metrics-scraper" "otelcollector" "${hr_ocsms_msg}" "${hr_ocsms_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts-metrics-scraper-lobby" "otelcollector" "${hr_ocsmsl_msg}" "${hr_ocsmsl_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts-watcher" "otelcollector" "${hr_ocsw_msg}" "${hr_ocsw_sta}"

            printf "+-----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf ""
            } >> diffTableReport.txt

            echo "" >> diffTableReport.txt
        """
        }
    
    }
    if (condition == "post-check"){
        // Check karpenter
        def helmToCheck = "karpenter"
        def hr_kar_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n karpenter -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_kar_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n karpenter -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check linkerd-control-plane
        helmToCheck = "linkerd-control-plane"
        def hr_lincp_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_lincp_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check linkerd-crds
        helmToCheck = "linkerd-crds"
        def hr_lincr_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_lincr_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n linkerd -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check prometheus-operator
        helmToCheck = "prometheus-operator"
        def hr_prom_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n monitoring -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_prom_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n monitoring -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check kube-state-metrics
        helmToCheck = "kube-state-metrics"
        def hr_ksm_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n monitoring -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ksm_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n monitoring -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector
        helmToCheck = "opentelemetry-collector"
        def hr_oc_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_oc_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-sts
        helmToCheck = "opentelemetry-collector-sts"
        def hr_ocs_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocs_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-deployment-logs
        helmToCheck = "opentelemetry-collector-deployment-logs"
        def hr_ocdl_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocdl_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-sts-metrics-scraper
        helmToCheck = "opentelemetry-collector-sts-metrics-scraper"
        def hr_ocsms_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocsms_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()
    
        // Check opentelemetry-collector-sts-metrics-scraper-lobby
        helmToCheck = "opentelemetry-collector-sts-metrics-scraper-lobby"
        def hr_ocsmsl_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocsmsl_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Check opentelemetry-collector-sts-watcher
        helmToCheck = "opentelemetry-collector-sts-watcher"
        def hr_ocsw_msg = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].message'").trim()
        def hr_ocsw_sta = sh(returnStdout: true, quiet: true, script: "kubectl get helmrelease ${helmToCheck} -n otelcollector -oyaml | yq '.status.conditions | sort_by(.lastTransitionTime)' | head -5 | yq '.[0].status'").trim()

        // Create table report
        dir("report") {
        sh """
            #!/bin/bash
            echo "" >> diffTableReport.txt
            {
            printf ""
            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf "| Helmrelease Checker Result                                                                                                                                     |\n"
            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "Helmrelease" "Namespace" "Message" "Status"
            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            
            # Add each row with aligned columns
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "karpenter" "karpenter" "${hr_kar_msg}" "${hr_kar_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "linkerd-control-plane" "linkerd" "${hr_lincp_msg}" "${hr_lincp_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "linkerd-crds" "linkerd" "${hr_lincr_msg}" "${hr_lincr_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "prometheus-operator" "monitoring" "${hr_prom_msg}" "${hr_prom_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "kube-state-metrics" "monitoring" "${hr_ksm_msg}" "${hr_ksm_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector" "otelcollector" "${hr_oc_msg}" "${hr_oc_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts" "otelcollector" "${hr_ocs_msg}" "${hr_ocs_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-deployment-logs" "otelcollector" "${hr_ocdl_msg}" "${hr_ocdl_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts-metrics-scraper" "otelcollector" "${hr_ocsms_msg}" "${hr_ocsms_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts-metrics-scraper-lobby" "otelcollector" "${hr_ocsmsl_msg}" "${hr_ocsmsl_sta}"
            printf "| %-52s | %-15s | %-69s | %-13s |\n" "opentelemetry-collector-sts-watcher" "otelcollector" "${hr_ocsw_msg}" "${hr_ocsw_sta}"

            printf "+----------------------------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf ""
            } >> diffTableReport.txt

            echo "" >> diffTableReport.txt
        """
        }

    }
}

def pushReport(){
    publishHTML(target: [
        reportDir               : "report",
        reportFiles             : "diffTableReport.txt",
        reportName              : "Diff Report",
        alwaysLinkToLastBuild   : true,
        allowMissing            : true,
        keepAll                 : true 
    ])
}

void createBanner(String message) {
  ansiColor('xterm'){
    echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    echo '\033[1;4;33m' + ":: ${message}"
    echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
  }
}

void coloredOutput(String key, value) {
  ansiColor('xterm'){
    echo "${key} : ${value}"
  }
}

