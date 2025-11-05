import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
	[
		parameters([
			choice(choices: envList, description: "Environment to check", name: "targetEnvironmentName"),
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

TARGET_ENVIRONMENT_NAME = params.targetEnvironmentName

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def binaryDir = "internal-infra-automation/hosting/rollout/Q1-2025/disaster-recovery/bin"
def envDirectory
def awsRegion
def awsAccountId
def (customer, project, environment) = targetEnvironmentName.split('-')
def targetHostname
currentBuild.displayName = "#${BUILD_NUMBER}-database-scraper-${targetEnvironmentName}"

node('hosting-agent') {
	container('tool') {
		stage('Pipeline Pre-Check'){
			if (TARGET_ENVIRONMENT_NAME == '' || TARGET_ENVIRONMENT_NAME == 'blank') {
				currentBuild.result = 'NOT_BUILT'
				buildStopped = true
				error('Aborting the build')
			}
			echo TARGET_ENVIRONMENT_NAME
		}
		if (!buildStopped){
			dir(tempDir){
				stage('Clone iac repo') {
					sshagent(['bitbucket-repo-read-only']) {
						// Clone IAC repo
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							git clone --quiet "git@bitbucket.org:accelbyte/iac.git" --depth 1 || true
							rm -rf iacTemp || true
							cp -R iac iacTemp || true
							chmod -R 777 iacTemp || true
						"""
					}
				}
				stage('Prepare tools') {
					sshagent(['bitbucket-repo-read-only']) {
						// Clone repositories
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							git clone --quiet "git@bitbucket.org:accelbyte/internal-infra-automation.git" --depth 1 || true
							chmod -R 777 internal-infra-automation || true
						"""

						dir(binaryDir) {
							sh """
								chmod +x inventory
								cp inventory /bin/inventory
							"""
						}
					}
				}

				stage('Set aws credentials'){
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

				stage('Running scripts') {
					dir(binaryDir) {
						sh """
							inventory -e ${targetEnvironmentName} -r ${awsRegion} -c config.json -o output.json
						"""
					}
				}

				stage('Output') {
					dir(binaryDir) {
						if(fileExists("output.json")){
							sh """
								set +x
								echo "fileUrl: \$(pwd)/output.json"
							"""
						} else {
							error: "File output.json not found"
						}
						
					}
				}

				stage('Archive Artifact') {
					dir(binaryDir) {
						archiveArtifacts artifacts: 'output.json', onlyIfSuccessful: true
					}
				}
			}
		}
	}
}