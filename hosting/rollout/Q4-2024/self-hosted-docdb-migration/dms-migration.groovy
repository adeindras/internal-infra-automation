import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: 'justice-shared', name: 'serviceGroup'),
			booleanParam(defaultValue: true, name: 'skipDownscale'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String serviceGroup = params.serviceGroup
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String serviceGroupSSMModified
serviceGroupSSMModified = serviceGroup.replace("-", "_")
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-migration"

node('hosting-agent') {
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

			stage('Prepare common variable') {
				ssmPath = sh(
					returnStdout: true,
					script: "kubectl -n justice get cm cluster-variables -oyaml | yq .data.SSM_PATH").trim()
				mongoIP = sh(
					returnStdout: true,
					script: "kubectl -n mongodb get po mongodb-0 -oyaml  | yq .status.podIP").trim()
				mongoPassword = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/mongodb_password\" \
						--with-decryption | jq .Parameter.Value
					""").trim()
				jumpBoxPod = sh(
					returnStdout: true,
					script: "kubectl -n tools get po -l app=jumpbox -o jsonpath='{.items[0].metadata.name}'").trim()
				listDatabases = sh(
					returnStdout: true,
					script: """
						set +x
						kubectl -n tools exec -i ${jumpBoxPod} -- mongo \
							--host=\"${mongoIP}:27017\" \
							--username root \
							--password \"${mongoPassword}\" \
							--eval \"db.getMongo().getDBNames()\" | tail -n +6 | grep -v admin | grep -v local | jq -r '[.[] | select(. != "admin" and . != "local" and . != "config")] | .[]'
					""").trim()
				serviceGroupName = sh(
					returnStdout: true,
					script: "cat iac/${envDirectory}/docdb/${serviceGroup}/terragrunt.hcl | grep \"service_group\" | awk {'print \$3'} | jq -r .").trim()
				docDBAddress = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/${serviceGroupSSMModified}_address\" \
						--with-decryption | jq '.Parameter.Value'
					""").trim()
				docDBUsername = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/${serviceGroupSSMModified}_username\" \
						--with-decryption | jq '.Parameter.Value'
					""").trim()
				docDBPassword = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/${serviceGroupSSMModified}_password\" \
						--with-decryption | jq '.Parameter.Value'
					""").trim()

				switch (null) {
					case ssmPath:
						currentBuild.result = 'FAILURE'
						break
					case mongoIP:
						currentBuild.result = 'FAILURE'
						break
					case mongoPassword:
						currentBuild.result = 'FAILURE'
						break
					case jumpBoxPod:
						currentBuild.result = 'FAILURE'
						break
					case listDatabases:
						currentBuild.result = 'FAILURE'
						break
					case serviceGroupName:
						currentBuild.result = 'FAILURE'
						break
					case docDBAddress:
						currentBuild.result = 'FAILURE'
						break
					case docDBUsername:
						currentBuild.result = 'FAILURE'
						break
					case docDBPassword:
						currentBuild.result = 'FAILURE'
						break
					default:
						echo "ssmPath = ${ssmPath}"
						echo "mongoIP = ${mongoIP}"
						// echo "mongoPassword = ${mongoPassword}"
						echo "jumpBoxPod = ${jumpBoxPod}"
						echo "databases = ${listDatabases}"
						echo "serviceGroupName = ${serviceGroupName}"
						echo "docDBAddress = ${docDBAddress}"
						echo "docDBUsername = ${docDBUsername}"
						// echo "docDBPassword = ${docDBPassword}"
						break
				}
			}

			stage("DMS test connection to MongoDB and DocDB") {
				if (params.skipMigration) {
					echo "Skip migrating task"
				} else { 
					replicationInstanceArn = sh(
							returnStdout: true,
							script: """
								set +x
								aws dms describe-replication-instances \
								--region ${awsRegion} \
								--output yaml | yq '.ReplicationInstances[0].ReplicationInstanceArn'
							""").trim()
					dmsEndpointArnsLists = sh(
							returnStdout: true,
							script: """
								aws dms describe-endpoints --region ${awsRegion} --output yaml | yq '.Endpoints[] | .EndpointArn'
							""").trim()

					switch (null) {
						case replicationInstanceArn:
							currentBuild.result = 'FAILURE'
							break
						case dmsEndpointArnsLists:
							currentBuild.result = 'FAILURE'
							break
						default:
							echo "replicationInstanceArn = ${replicationInstanceArn}"
							echo "dmsEndpointArnsLists = ${dmsEndpointArnsLists}"
							break
					}
					
					def dmsEndpointArns = dmsEndpointArnsLists.split('\n')
					for (dmsEndpointArn in dmsEndpointArns) {
							echo "Starting DMS Test Connection for ${dmsEndpointArn}"
							sh """
									set +x
									aws dms test-connection \
									--replication-instance-arn ${replicationInstanceArn} \
									--endpoint-arn ${dmsEndpointArn} \
									--region ${awsRegion}
							"""
							sleep(3)
					}

					def allConnectionsSuccessful = false
						while (!allConnectionsSuccessful) {
							allConnectionsSuccessful = true

							for (dmsEndpointArn in dmsEndpointArns) {
									testConnectionResult = sh(
											returnStdout: true,
											script: """
													aws dms describe-connections \
													--region ${awsRegion} \
													--filter "Name=endpoint-arn,Values=${dmsEndpointArn}" \
													--query 'Connections[0].Status' --output text --no-cli-pager
											"""
									).trim()

									echo "Connection status for ${dmsEndpointArn}: ${testConnectionResult}"

									if (testConnectionResult == "failed") {
											failedEndpointPointIdentifier = sh(
												returnStdout: true,
												script: """
													set +x
													aws dms describe-endpoints \
													--region ${awsRegion} \
													--output yaml | yq '.Endpoints[] | select(.EndpointArn == "${dmsEndpointArn}").EndpointIdentifier'
												""").trim()
											error("Connection Identifier ${failedEndpointPointIdentifier} failed!")
											currentBuild.result = 'FAILURE'
											break 
									} else if (testConnectionResult != "successful") {
											allConnectionsSuccessful = false
									}
							}

							if (!allConnectionsSuccessful) {
									sleep(5)
							}
						}

						echo "All connections are successful!"
				}
			}

			stage("Scale down services (using mongo) to 0") {
				if (params.skipDownscale) {
					echo "Skip downscale, should be non-disruptive"
				} else {
					externalSecretYaml = sh(
						returnStdout: true,
						script: "kubectl -n justice get externalsecret -oyaml"
					).trim()
					
					writeFile(file: "external-secret-${targetEnvironmentName}.yaml", text: externalSecretYaml)
					sh "ls -lah ."
					
					listsMongoServices = sh(
						returnStdout: true,
						script: """
							yq '.items[] | select(.spec.data[]?.secretKey == "*MONGO*") | .metadata.name' external-secret-${targetEnvironmentName}.yaml | grep -v flux | grep -v job | awk -F'-secret' '{print \$1}'
						""").trim().split('\n')
					
					echo "list impacted services"
					echo "${listsMongoServices}"
					for (service in listsMongoServices) {
						sh "kubectl -n justice get po | grep ${service}"
						sh "kubectl -n justice patch pdb ${service} --type='merge' -p '{\"spec\":{\"minAvailable\":0}}' || true"
						sh "kubectl -n justice scale deploy ${service} --replicas=0 || true"
					}
					sleep(30)
				}
			}

			stage('Start Migrating Task') {
				if (params.skipMigration) {
					echo "Skip migrating task"
				} else { 
					def userInput = input(
							id: 'userInput', 
							message: 'Before starting the migration process, ensure the replicas scale down to 0', 
							parameters: [
									[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
							]
					)

					if(!userInput) {
							error "Build failed, not confirmed"
					}

					replicationTasksArn = sh(returnStdout: true, script: """
							aws dms describe-replication-tasks \
							--region ${awsRegion} \
							--output yaml | yq '.ReplicationTasks[] | .ReplicationTaskArn'
					""").trim()
					
					echo "${replicationTasksArn}"
					def replicationTasks = replicationTasksArn.split('\n')
					
					for (replicationTaskArn in replicationTasks) {
						sh "echo ${replicationTaskArn}"
						
						// Get the current task status
						def taskStatus = sh(returnStdout: true, script: """
							aws dms describe-replication-tasks \
							--region ${awsRegion} \
							--output yaml | yq '.ReplicationTasks[] | select(.ReplicationTaskArn == "${replicationTaskArn}") | .Status'
						""").trim()
						
						if (taskStatus == 'ready') {
							echo "Starting replication for task ${replicationTaskArn} (ready)"
							sh """
								aws dms start-replication-task \
								--region ${awsRegion} \
								--replication-task-arn ${replicationTaskArn} \
								--start-replication-task-type start-replication
							"""
						} else {
							echo "Reloading target for task ${replicationTaskArn} (stopped)"
							sh """
								aws dms start-replication-task \
								--region ${awsRegion} \
								--replication-task-arn ${replicationTaskArn} \
								--start-replication-task-type reload-target
							"""
						}
						sleep(30)
					}

					for (replicationTaskArn in replicationTasks) {
						sh "echo ${replicationTaskArn}"
						
						// Wait for each task to complete
						while (true) {
							def replicationTaskStatus = sh(returnStdout: true, script: """
								aws dms describe-replication-tasks \
								--region ${awsRegion} \
								--output yaml | yq '.ReplicationTasks[] | select(.ReplicationTaskArn == "${replicationTaskArn}") | .Status'
							""").trim()
							
							def replicationTaskStopReason = sh(returnStdout: true, script: """
								aws dms describe-replication-tasks \
								--region ${awsRegion} \
								--output yaml | yq '.ReplicationTasks[] | select(.ReplicationTaskArn == "${replicationTaskArn}") | .StopReason'
							""").trim()

							if (replicationTaskStatus == "stopped" && replicationTaskStopReason == "Stop Reason FULL_LOAD_ONLY_FINISHED") {
								echo "Task ${replicationTaskArn} migration is successful"
								break
							}
							
							if (replicationTaskStatus == "failed" && replicationTaskStopReason != "Stop Reason FULL_LOAD_ONLY_FINISHED") {
								echo "Task ${replicationTaskArn} failed. Check the dashboard"
								echo "Replication task ${replicationTaskArn} failed"
								echo "Please migrate failed job through AWS Dashboard/DMS"
								echo "https://us-east-2.console.aws.amazon.com/dms/v2/home?region=us-east-2#tasks"
								break
							}
							sleep(10) // Poll every 10 seconds
						}
					}
				}
			}
		}
	}
}