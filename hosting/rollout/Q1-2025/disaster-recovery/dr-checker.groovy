import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
	[
		parameters([
			choice(
				choices: envList,
				description: "Environment to check",
				name: "targetEnvironmentName"
			),
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


def confirm(){
	def userInput = input(
		id: 'userInput', 
		message: 'Please review carefully, and ensure this doesnt destroy anything', 
		parameters: [
			[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
		]
	)
	
	if(!userInput) {
		error "Build failed not confirmed"
	}
}

def tempDir = "tmpdir${BUILD_NUMBER}"
def envDirectory
def awsRegion
def awsAccountId
def (customer, project, environment) = targetEnvironmentName.split('-')

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-dr-checker"
String requirementStatementPath="hosting/rollout/Q1-2025/disaster-recovery/statement-policy.yaml"
String scriptChecker="hosting/rollout/Q1-2025/disaster-recovery/script.sh"
String disasterRecoveryDir="hosting/rollout/Q1-2025/disaster-recovery"

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

			stage('Clone internal-infra-automation') {
				sshagent(['bitbucket-repo-read-only']) {
					// Clone repositories
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						git clone --quiet "git@bitbucket.org:accelbyte/internal-infra-automation.git" --depth 1 || true
						chmod -R 777 internal-infra-automation || true
					"""
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

			// Running Script Checker
			stage("Running Checker Script") {
				dir("internal-infra-automation/$disasterRecoveryDir") {
						def pushReport = {
								publishHTML(target: [
										reportDir             : "report",
										reportFiles           : "DbTableReport.txt",
										reportName            : "Disaster Recovery Report",
										alwaysLinkToLastBuild : true,
										allowMissing          : false,
										keepAll               : true
								])
						}

						sh """
								echo "Running the checker script..."
								./script.sh -r ${awsRegion} -id ${awsAccountId} -e ${targetEnvironmentName}

								mkdir report
								cp DbTableReport.txt report/DbTableReport.txt
						"""

						pushReport()
				}
			}
		}
	}
}