import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: '', name: 'stringPattern'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String stringPattern = params.stringPattern
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-elasticache-verification"
def podsContainers = []

node('infra-sizing') {
	container('tool') {
		dir(tempDir){
			stage('Clone iac repository') {
				sshagent(['bitbucket-repo-read-only']) {
					// Clone IAC repo
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						git clone --quiet "git@bitbucket.org:accelbyte/iac.git" || true
						chmod -R 777 iac || true
						rm -rf ~/.aws/config || true
					"""
				}
			}
			
			stage('Set aws credentials'){
				def (customer, project, environment) = targetEnvironmentName.split('-')
				dir('iac') {
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

			stage("Verifying Services") {
				externalSecretYaml = sh(
						returnStdout: true,
						script: "kubectl -n justice get externalsecret -oyaml"
				).trim()
				writeFile(file: "external-secret-${targetEnvironmentName}.yaml", text: externalSecretYaml)
				listsElasticacheServices = sh(
					returnStdout: true,
					script: """
						yq '.items[] | select(.spec.data[]?.secretKey == "*REDIS*") | .metadata.name' external-secret-${targetEnvironmentName}.yaml | grep -v flux | grep -v job | awk -F'-secret' '{print \$1}'
					"""
				).trim().split('\n')

				sh "touch pods-running-${targetEnvironmentName}-${timeStamp}.yaml"

				for (service in listsElasticacheServices) {
					sh """
						kubectl -n justice get pod -l 'app=${service}' -o yaml | yq '.items[] | select(.status.phase == "Running" and (.metadata.name | contains("job") | not)) | .metadata.name' >> pods-running-${targetEnvironmentName}-${timeStamp}.yaml || true
					"""
				}
				sh "sed -i '/^\$/d' pods-running-${targetEnvironmentName}-${timeStamp}.yaml"
				sh "cat pods-running-${targetEnvironmentName}-${timeStamp}.yaml"

				def pods = readFile("pods-running-${targetEnvironmentName}-${timeStamp}.yaml").trim().split('\n')
				
				for (pod in pods) {
					containers = sh(
						returnStdout: true,
						script: """
							kubectl -n justice get pod "${pod}" -o yaml | yq eval '.spec.containers[].name' - | grep -Ev 'opentelemetry|init|linkerd' | awk -v pod="${pod}" '{print pod"---"\$0}'
						"""
					).trim().split('\n')

					podsContainers.addAll(containers)
				}

				for (podContainer in podsContainers){
					def (pod, container) = podContainer.split('---')
					sh """
					  if \$(kubectl -n justice exec -t ${pod.trim()} -c ${container.trim()} -- printenv | grep -q "${stringPattern}"); then {
					      >&2 echo "Error: found pattern ${stringPattern} on this pod ${pod}"
					  }
					  else {
					      echo "Not found pattern ${stringPattern} on this pod ${pod}"
					  }
					  fi
						echo "-----------------------------------------------------"
					"""
				}
			}
		}
	}
}