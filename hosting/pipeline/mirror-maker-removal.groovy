import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName', description: 'please follow the naming of eks cluster (i.e sandbox-justice-dev)'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String awsAccountId
String awsRegion
String envDirectory
def tempDir = "tmpdir${BUILD_NUMBER}"
def CLUSTER_PATH
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}"

node('hosting-agent') {
	container('tool') {
		dir(tempDir) {
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
					""").trim()
					awsAccountId = sh(returnStdout: true, script: """
						echo ${envDirectory} | egrep -o '[[:digit:]]{12}'
					""").trim()
					awsRegion = sh(returnStdout: true, script: """
						basename \$(dirname ${envDirectory})
					""").trim()
				
					awsAccessMerged = sh( returnStdout: true, script: """
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
					""").trim()
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

			stage('Remove mirror maker and zookeeper') {
				// Check if the kafka namespace exists and capture the phase status
				def kafkaNamespace = sh(returnStdout: true, script: """
					kubectl get ns kafka -o jsonpath='{.status.phase}' || echo "NotFound"
				""").trim()

				if (kafkaNamespace == "Active") {
					def checkMirrorMakerReplicas = sh(returnStdout: true, script: """
						kubectl -n kafka get sts mirror-maker-kafka -o jsonpath='{.status.replicas}' || echo "NotFound"
					""").trim()

					def checkZookeeperMirrorMakerReplicas = sh(returnStdout: true, script: """
						kubectl -n kafka get sts mirror-maker-zookeeper -o jsonpath='{.status.replicas}' || echo "NotFound"
					""").trim()
					
					switch (checkMirrorMakerReplicas || checkZookeeperMirrorMakerReplicas) {
						case 'NotFound':
						case '':
							echo "Mirror maker is removed or not found"
							break
						case '1':
							echo "Scaling down and removing mirror maker and zookeeper mirror maker"
							while (true) {
								def currentMirrorMakerReplicas = sh(returnStdout: true, script: """
									kubectl -n kafka get sts mirror-maker-kafka -o jsonpath='{.status.replicas}' || echo "NotFound"
								""").trim()

								def currentZookeeperMirrorMakerReplicas = sh(returnStdout: true, script: """
									kubectl -n kafka get sts mirror-maker-zookeeper -o jsonpath='{.status.replicas}' || echo "NotFound"
								""").trim()

								sh """
									kubectl patch -n flux-system kustomizations.kustomize.toolkit.fluxcd.io mirror-maker -p '{"spec":{"prune":true}}' --type=merge || true
									kubectl -n kafka delete sts mirror-maker-kafka || true
									kubectl -n kafka delete sts mirror-maker-zookeeper || true
									kubectl -n flux-system delete ks mirror-maker || true
								"""
								echo "Status Mirror Maker Replicas: ${currentMirrorMakerReplicas}"
								echo "Status Zookeeper Mirror Maker Replicas: ${currentZookeeperMirrorMakerReplicas}"

								sleep(10)

								if((currentMirrorMakerReplicas == 0 || currentMirrorMakerReplicas == "NotFound") && (currentZookeeperMirrorMakerReplicas == 0 || currentZookeeperMirrorMakerReplicas == "NotFound")){
									break
								}
							}
							break
						default:
							echo "Checking mirror maker status"
							while (true) {
								def currentMirrorMakerReplicas = sh(returnStdout: true, script: """
									kubectl -n kafka get sts mirror-maker-kafka -o jsonpath='{.status.replicas}' || echo "NotFound"
								""").trim()

								def currentZookeeperMirrorMakerReplicas = sh(returnStdout: true, script: """
									kubectl -n kafka get sts mirror-maker-zookeeper -o jsonpath='{.status.replicas}' || echo "NotFound"
								""").trim()

								sh """
									kubectl patch -n flux-system kustomizations.kustomize.toolkit.fluxcd.io mirror-maker -p '{"spec":{"prune":true}}' --type=merge || true
									kubectl -n kafka delete sts mirror-maker-kafka || true
									kubectl -n kafka delete sts mirror-maker-zookeeper || true
									kubectl -n flux-system delete ks mirror-maker || true
								"""
								echo "Status Mirror Maker Replicas: ${currentMirrorMakerReplicas}"
								echo "Status Zookeeper Mirror Maker Replicas: ${currentZookeeperMirrorMakerReplicas}"

								sleep(10)

								if((currentMirrorMakerReplicas == 0 || currentMirrorMakerReplicas == "NotFound") && (currentZookeeperMirrorMakerReplicas == 0 || currentZookeeperMirrorMakerReplicas == "NotFound")){
									break
								}
							}
							break
					}
				} else if (kafkaNamespace == "NotFound") {
					echo "Namespace kafka not found. Mirror maker doesn't exist."
				} else {
					echo "Kafka namespace is in an unexpected state: ${kafkaNamespace}"
				}
			}
		}
	}
}
