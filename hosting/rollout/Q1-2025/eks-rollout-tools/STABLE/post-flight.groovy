import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
	[
		parameters([
			choice(choices: envList, description: "Environment to migrate", name: "targetEnvironmentName"),
			choice(choices: ['addon', 'controlplane', 'workernode'], description: "Kubernetes component", name: "k8sComponent")
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

def tempDir = "tmpdir${BUILD_NUMBER}"
String targetEnvironmentName = params.targetEnvironmentName
String k8sComponent = params.k8sComponent
String envDirectory
String eksRolloutToolsDir="hosting/rollout/Q1-2025/eks-rollout-tools/STABLE"
String eksYaml="scripts/eks-helper/eks.yaml"
String reportFileName="DbTablePostflightReport.txt"
currentBuild.displayName = "#${BUILD_NUMBER}-post-flight-${k8sComponent}-${targetEnvironmentName}"

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

			// Running Script Postflight
			stage("Running Postflight Script") {
				sh "cp iac/scripts/eks-helper/eks.yaml internal-infra-automation/$eksRolloutToolsDir/script/eks.yaml"
				dir("internal-infra-automation/$eksRolloutToolsDir/script") {
						def pushReport = {
								publishHTML(target: [
										reportDir             : "report",
										reportFiles           : "${reportFileName}",
										reportName            : "Postflight Report ${k8sComponent}",
										alwaysLinkToLastBuild : true,
										allowMissing          : false,
										keepAll               : true
								])
						}

						sh """
								echo "Running post-flight script..."
								./post-flight-script.sh -r ${awsRegion} -id ${awsAccountId} -e ${targetEnvironmentName} -c ${k8sComponent}

								mkdir report
								ls -lah .
								cp ${reportFileName} report/${reportFileName}
						"""

						pushReport()
				}
			}
		}
	}
}