import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
  [
    parameters([
	  choice(choices: envList, description: "Environment to migrate", name: "targetEnvironmentName"),
	  choice(choices: ["tier-1","tier-2","tier-3","tier-4"].join("\n"), name: 'tierSetup', description: 'Tier wants to be used')
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
TIER_SETUP = params.tierSetup
TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName

def ccuTierData() {
    tierToCCU = ""
    switch(TIER_SETUP){
        case "tier-1":
            tierToCCU = "<1k"
            break
        case "tier-2":
            tierToCCU = "1k - 20k"
            break
        case "tier-3":
            tierToCCU = "20k - 120k"
            break
        case "tier-4":
            tierToCCU = "500k - 1000k"
            break
        default:
            tierToCCU = "0"
            break
    }
    echo "${tierToCCU}"
    return tierToCCU
}

String targetEnvironmentName = params.targetEnvironmentName
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME
String MANIFEST_DIR
String SUB_MANIFEST_DIR
String iacBranchURL = "https://bitbucket.org/accelbyte/iac/branch"
def prHtmlLink
def slackChannel
def TIER_TO_CCU
def buildStopped = false
currentBuild.displayName = "#${BUILD_NUMBER}-infra-migration-${targetEnvironmentName}"
def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
def WORKSPACE_STATE

node('hosting-agent') {
	container('tool') {
        stage('Pipeline Pre-Check'){
            if (TIER_SETUP == '' || TIER_SETUP == 'blank' ) {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (TARGET_ENVIRONMENT_NAME == '' || TARGET_ENVIRONMENT_NAME == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }

            echo TIER_SETUP
            echo TARGET_ENVIRONMENT_NAME
            if (WORKSPACE.contains("DEVELOPMENT")) {
                slackChannel = "C07UY55SE20"
				WORKSPACE_STATE = "DEVELOPMENT"
            } else {
                slackChannel = "C080SRE92NA"
				WORKSPACE_STATE = "STABLE"
            }
        }
        if (!buildStopped) {
			dir(tempDir){
				def (customer, project, environment) = targetEnvironmentName.split('-')
				stage('Clone repositories') {
					sshagent(['bitbucket-repo-read-only']) {
						// Clone repositories
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							git clone --quiet "git@bitbucket.org:accelbyte/iac.git" --depth 1 || true
							rm -rf iacTemp || true
							cp -R iac iacTemp || true

							git clone --quiet "git@bitbucket.org:accelbyte/internal-infra-automation.git" --depth 1 || true
							cp -R internal-infra-automation/hosting/rollout/Q4-2024/ags-infra-deployment/${WORKSPACE_STATE}/basefile iacTemp/basefileTemp || true
							rm -rf internal-infra-automation || true
						"""
					}
				}


				stage('Deploy ags-infrastructure manifest'){
					dir('iacTemp'){
						// Getting cluster directory information
						iacDirectory = sh(returnStdout: true, script: """
							clusterDir=\$(find live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep ${environment} | grep -v terragrunt-cache | head -n 1)
							dirname \${clusterDir}
						"""
						).trim()
						awsAccountId = sh(returnStdout: true, script: """
							echo ${iacDirectory} | egrep -o '[[:digit:]]{12}'
						"""
						).trim()
						awsRegion = sh(returnStdout: true, script: """
							basename \$(dirname ${iacDirectory})
						"""
						).trim()

						sh """
							echo Cluster Information parsed!
						"""

						BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-${timeStamp}"
						MANIFEST_DIR = "manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync"
						SUB_MANIFEST_DIR = "manifests/clusters/${customer}/${project}/${awsRegion}/${environment}"

						// Copy Manifest
						sh """
							cp basefileTemp/ags-infrastructure.yaml ${MANIFEST_DIR}/extended
							echo Base Manifest copied!

							echo >> ${MANIFEST_DIR}/core/repo.yaml
							cat basefileTemp/ags-infra-git.yaml >> ${MANIFEST_DIR}/core/repo.yaml
							echo Git Manifest copied!
							
							chmod +x basefileTemp/manifest_replacer.sh
							cp basefileTemp/manifest_replacer.sh ${MANIFEST_DIR}
						"""

						// Add linkerd annotations for justice namespace
						dir ("${SUB_MANIFEST_DIR}/rbac") {
							dir("namespaces/justice/"){
								sh """
									yq eval '(.metadata.annotations."config.alpha.linkerd.io/proxy-wait-before-exit-seconds") = "40"' -i namespace.yaml
									yq eval '(.metadata.annotations."config.linkerd.io/skip-outbound-ports") = "5432,6379,9092,9094,9096,27017"' -i namespace.yaml
								"""
							}
						}

						// Adjusting kustomization
						dir ("${MANIFEST_DIR}") {
							dir ("extended") {
								sh """#!/bin/bash
									# Disable websocket and otelcollector entry via kustomization comment
									sed -i '/emissary-ingress-websocket.yaml/s/^/  # /' linkerd/kustomization.yaml || true
									sed -i '/linkerd.yaml/s/^/  # /' linkerd/kustomization.yaml || true
									sed -i '/emissary-ingress.yaml/s/^/  # /' linkerd/kustomization.yaml || true
									sed -i '/websocket/s/^/  # /' kustomization.yaml
									sed -i '/opentelemetry-collector/s/^/  # /' kustomization.yaml

									# Remove linkerd and emissary-ingress block under linkerd.yaml
									yq eval 'select(.metadata.name != "linkerd" and .metadata.name != "emissary-ingress")' linkerd.yaml > temp.yaml && \
									yq eval 'select(.metadata.name == "linkerd" or .metadata.name == "emissary-ingress")' linkerd.yaml | sed 's/^/# /' >> temp.yaml && \
									mv temp.yaml linkerd.yaml

									# Add new kustomization
									yq -i '.bases += ["./ags-infrastructure.yaml"]' kustomization.yaml
								"""
							}
							dir ("core") {
								sh """
									# Remove logging-fluentd under logging.yaml
									yq eval 'select(.metadata.name != "logging-fluentd")' logging.yaml > temp.yaml && \
									yq eval 'select(.metadata.name == "logging-fluentd")' logging.yaml | sed 's/^/# /' >> temp.yaml && \
									mv temp.yaml logging.yaml

									# Disable karpenter entry via kustomization comment
									sed -i '/karpenter/s/^/  # /' kustomization.yaml

									# Split KSM from prom-stack
									yq eval '(select(.metadata.name == "prom-stack").spec.postBuild.substitute.KUBE_STATE_METRICS_ENABLED) = "false"' -i monitoring.yaml
								"""
							}
						}
						// Remove old dependency manifest
						dir("${MANIFEST_DIR}") {
							sh """
								./manifest_replacer.sh
								rm manifest_replacer.sh
							"""
						}
					}
				}

				stage('Additional check'){
					dir("iacTemp/${MANIFEST_DIR}/extended"){
						if(environment.equals("prod") ) {
							sh """
								yq -i '(. | select(.metadata.name == "ags-karpenter-templates") | .spec.path) = "./custom/${customer}/${environment}/karpenter-templates/tier-1"' ags-infrastructure.yaml
							"""
						}
						// Adjust tier on ags-infrastcuture.yaml
						sh """
							sed -i 's/tier-1/${tierSetup}/g' ags-infrastructure.yaml
						"""
					}
				}

				stage('Commit and push iac repo'){
					sshagent(['bitbucket-repo-read-only']) {
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							cp -R iacTemp/${SUB_MANIFEST_DIR}/. iac/${SUB_MANIFEST_DIR}
							cd iac
							chmod 644 ${SUB_MANIFEST_DIR} || true
							git checkout -b ${BB_BRANCH_NAME}
							git config --global user.email "build@accelbyte.net"
							git config --global user.name "Build AccelByte"
							cd ${SUB_MANIFEST_DIR}
							git add .
							git commit -m "feat: ${BB_BRANCH_NAME}"
							git push origin ${BB_BRANCH_NAME}
						"""
					}
				}

				stage("Create PR iac repo") {
					prSummary="""
	:: AGS Infra Manifest Migration \n \n
	:: Environment : ${targetEnvironmentName} \n \n
	:: Warning! Might cause connection disruption and service restart \n \n
					"""
					withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
						// POST
						def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
						def postData =  [
							title: "feat: ags-infra-migration ${targetEnvironmentName}",
							source: [
								branch: [
									name: "${BB_BRANCH_NAME}"
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
						post.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
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
			}
			stage('Sending Slack Notification'){
				TIER_TO_CCU = ccuTierData();
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
									text: ":arrow_forward: AGS INFRA MIGRATION :arrow_forward: \n*<${BUILD_URL}console|Go to Jenkins now!>*"
								]
							], 
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Moved to Tier:*\n${tierSetup}"
									],
									[
										type: "mrkdwn",
										text: "*Targeted CCU:*\n${TIER_TO_CCU}"
									],
									[
										type: "mrkdwn",
										text: "*Environment:*\n${targetEnvironmentName}"
									],
									[
										type: "mrkdwn",
										text: "*Triggered by:*\n${userId}"
									],
									[
										type: "mrkdwn",
										text: "*Branch:*\n<${iacBranchURL}/${BB_BRANCH_NAME}|${BB_BRANCH_NAME}>"
									],
									[
										type: "mrkdwn",
										text: "*PR:*\n<${prHtmlLink}|Click Here>"
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
}

