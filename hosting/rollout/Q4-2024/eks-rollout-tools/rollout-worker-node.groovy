import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			booleanParam(defaultValue: false, name: 'skipRolloutCriticalOnInit'),
			booleanParam(defaultValue: false, name: 'skipRolloutSts'),
			booleanParam(defaultValue: false, name: 'skipRolloutEmissary'),
			booleanParam(defaultValue: false, name: 'skipRolloutArmadaServices'),
			booleanParam(defaultValue: false, name: 'skipRolloutJusticeDeployment'),
			booleanParam(defaultValue: false, name: 'skipRolloutRemainingDeployment'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
Boolean skipRolloutCriticalOnInit = params.skipRolloutCriticalOnInit 
Boolean skipRolloutSts = params.skipRolloutSts
Boolean skipRolloutEmissary = params.skipRolloutEmissary
Boolean skipRolloutArmadaServices = params.skipRolloutArmadaServices
Boolean skipRolloutJusticeDeployment = params.skipRolloutJusticeDeployment
Boolean skipRolloutRemainingDeployment = params.skipRolloutRemainingDeployment
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String targetDesiredCapacity
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String asgCriticalOnInit
String defaultMaxSizeCapacityASG
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

			stage('Scale Up critical-on-init') {
				echo "Scale Up critical-on-init"
				if(params.skipRolloutCriticalOnInit) {
					echo "Skip Rollout Critical-on-init services"
				} else {
					def (customer, project, environment) = targetEnvironmentName.split('-')
					asgCriticalOnInit = sh( returnStdout: true, script: """
						aws autoscaling describe-auto-scaling-groups \
						--region "${awsRegion}" \
						--query "AutoScalingGroups[*]" \
						--output yaml \
						--no-cli-pager \
						| yq -r '.[] | select(.AutoScalingGroupName | contains("critical-on-init") and .AutoScalingGroupName | contains("${environment}") and .Instances | length >= 2) | .AutoScalingGroupName'
					""").trim()

					if(asgCriticalOnInit == "" || asgCriticalOnInit == "null" || asgCriticalOnInit) {
						echo "ASG Critical On Init not found, exiting"
						exit 1
					}

					defaultDesiredCapacityASG = sh( returnStdout: true, script: """
						aws autoscaling describe-auto-scaling-groups \
						--auto-scaling-group-name ${asgCriticalOnInit} \
						--region ${awsRegion} \
						--output yaml \
						--no-cli-pager  | yq '.AutoScalingGroups[0].DesiredCapacity'
					""").trim().toInteger()

					defaultMaxSizeCapacityASG = sh( returnStdout: true, script: """
						aws autoscaling describe-auto-scaling-groups \
						--auto-scaling-group-name ${asgCriticalOnInit} \
						--region ${awsRegion} \
						--output yaml \
						--no-cli-pager | yq '.AutoScalingGroups[0].MaxSize'
					""").trim().toInteger()

					targetDesiredCapacity = defaultDesiredCapacityASG * 2

					echo "Set Max ASG ${asgCriticalOnInit} ${targetDesiredCapacity}"
					while(true) {
						lastStatusASG = sh( returnStdout: true, script: """
						aws autoscaling describe-scaling-activities \
						--auto-scaling-group-name ${asgCriticalOnInit} \
						--region ${awsRegion} \
						--output yaml \
						--no-cli-pager | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
						""").trim()

						if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
							echo "asgCriticalOnInit = ${asgCriticalOnInit}"

							sh """
								echo "Increase ASG critical-on-init ${asgCriticalOnInit}"
								aws autoscaling update-auto-scaling-group \
								--auto-scaling-group-name ${asgCriticalOnInit} \
								--max-size ${targetDesiredCapacity} \
								--region ${awsRegion}
							"""
							break
						} else {
							echo "INFO - Waiting status scaling activity on ${asgCriticalOnInit} to ready"
							sleep(5)
						}
					}

					echo "Set New Protected Instance In ASG ${asgCriticalOnInit}"
					while(true) {
						lastStatusASG = sh( returnStdout: true, script: """
						aws autoscaling describe-scaling-activities \
						--auto-scaling-group-name ${asgCriticalOnInit} \
						--region ${awsRegion} \
						--output yaml \
						--no-cli-pager | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
						""").trim()

						if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
							echo "asgCriticalOnInit = ${asgCriticalOnInit}"

							sh """
								aws autoscaling update-auto-scaling-group \
								--auto-scaling-group-name ${asgCriticalOnInit} \
								--new-instances-protected-from-scale-in \
								--region ${awsRegion}
							"""
							break
						} else {
							echo "INFO - Waiting status scaling activity on ${asgCriticalOnInit} to ready"
							sleep(5)
						}
					}

					echo "Set desired ASG ${asgCriticalOnInit} to ${targetDesiredCapacity}"
					while(true) {
						lastStatusASG = sh(returnStdout: true, script: """
							aws autoscaling describe-scaling-activities \
							--auto-scaling-group-name ${asgCriticalOnInit} \
							--region ${awsRegion} \
							--output yaml \
							--no-cli-pager | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
						""").trim()

						if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
							echo "Scaling activity completed or none in progress for ASG: ${asgCriticalOnInit}"

							while(true) {
								tryScaleUp = sh(returnStdout: true, script: """
	
									aws autoscaling set-desired-capacity \
									--auto-scaling-group-name ${asgCriticalOnInit} \
									--desired-capacity ${targetDesiredCapacity} \
									--region ${awsRegion} || echo "ScalingActivityInProgress"
								""").trim()

								sleep(3)

								if (tryScaleUp.contains("ScalingActivityInProgress")) {
									echo "Scaling activity is still in progress. Retrying in 10 seconds..."
									sleep(10) 
								} else {
									echo "Scaling successful, wait until worker nodes are ready"
									break
								}
							}
							break 
						} else {
							echo "INFO - Waiting status scaling activity on ${asgCriticalOnInit} to ready"
							sleep(5)
						}
					}

					sleep(30)

					echo "Verifying worker node ASG ${asgCriticalOnInit}"
					while(true) {
						currentInstancesInASG = sh(returnStdout: true, script: """
							set +x
							aws autoscaling describe-auto-scaling-groups \
							--region ${awsRegion} \
							--query "AutoScalingGroups[*]" \
							--output yaml \
							| yq -r '.[] | select(.AutoScalingGroupName == "${asgCriticalOnInit}" and .Instances[].HealthStatus == "Healthy" and .Instances[].LifecycleState == "InService") | .Instances | length'
						""").trim()

						currentCriticalOnInitWorkerNode = sh(returnStdout: true, script: """
							kubectl get nodes -o yaml | yq '[.items[] | select(.metadata.labels."accelbyte.io/workload" == "CriticalOnInit")] | length'
						""").trim()

						if (currentInstancesInASG.toInteger() == targetDesiredCapacity.toInteger() && currentCriticalOnInitWorkerNode.toInteger() == targetDesiredCapacity.toInteger()) {
							echo "Worker Node (CriticalOnInit) is Healthy"
							break
						} else {
							sleep 30
						}
					}
				}
			}

			stage('Cordon all nodes') {
				echo "Start cordon old worker nodes"
				oldWorkerNodesList = sh(returnStdout: true, script: """
					kubectl get no -oyaml | yq '.items[] | select(.status.nodeInfo.kubeletVersion | contains("1.30") | not) | .metadata.name'
				""").trim()

				if (oldWorkerNodesList) {
					def oldWorkerNodes = oldWorkerNodesList.split('\n')
					
					def nodesString = oldWorkerNodes.join(' ')
					sh """
							kubectl cordon ${nodesString}
					"""
				} else {
					error "ERROR: old worker node not found"
				}
			}

			stage('Rollout critical-on-init services') {
				if(params.skipRolloutCriticalOnInit) {
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
				if(params.skipRolloutCriticalOnInit) {
					echo "Skip Rollout Critical-on-init services"
				} else {
					echo "Verifying karpenter services"
					karpenterReplicasCount = sh( returnStdout: true, script: """
						kubectl -n karpenter get deploy karpenter -oyaml | yq '.status.readyReplicas'
					""").trim()

					while(true) {
						karpenterNodesPlacement = sh( returnStdout: true, script: """
							kubectl -n karpenter get po -oyaml | yq '.items[] | .spec.nodeName'
						""").trim().split('\n')

						for (karpenterNode in karpenterNodesPlacement) {
							isKarpenterNodeInCordon = sh( returnStdout: true, script: """
								kubectl get no -oyaml | yq '.items[] | select(.metadata.name == "${karpenterNode}") | .spec.unschedulable'
							""").trim()

							if (isKarpenterNodeInCordon == "true") {
								echo "There is karpenter pods in old node"
								sh "kubectl -n karpenter rollout restart deployment"
								echo "WARNING: Karpenter in old node"
							} else if (isKarpenterNodeInCordon == "null" || isKarpenterNodeInCordon == "false") {
								echo "Karpenter placed in new node"
							}
							sleep(3)
						}
						break
					}

					echo "Verifying flux-system services"
					fluxSystemDeployments = sh( returnStdout: true, script: """
						kubectl -n flux-system get deploy -oyaml | yq '.items[] | select(.metadata.name | contains("controller")) | .metadata.name'
					""").trim().split('\n')

					for (fluxSystemDeployment in fluxSystemDeployments) {
						fluxSystemNodesPlacement = sh( returnStdout: true, script: """
							kubectl -n flux-system get pod -oyaml | yq '.items[] | select(.metadata.labels.app == "${fluxSystemDeployment}") | .spec.nodeName'
						""").trim()

						isFluxSystemPodsInCordon = sh( returnStdout: true, script: """
							kubectl get no -oyaml | yq '.items[] | select(.metadata.name == "${fluxSystemNodesPlacement}") | .spec.unschedulable'
						""").trim()

						if (isFluxSystemPodsInCordon == true || isFluxSystemPodsInCordon == "true") {
							echo "WARNING: ${fluxSystemDeployment} in old node"
						} else {
							echo "Flux system pods (${fluxSystemDeployment}) in new node"
						}
					}
				}
			}

			stage('Scale Down critical-on-init') {
				if(params.skipRolloutCriticalOnInit) {
					echo "Skip Rollout Critical-on-init services"
				} else {
					while(true) {
						lastStatusASG = sh( returnStdout: true, script: """
						aws autoscaling describe-scaling-activities \
						--auto-scaling-group-name ${asgCriticalOnInit} \
						--region ${awsRegion} \
						--output yaml | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
						""").trim()

						if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
							sh """
							echo "set no-new-protected-instance asgCriticalOnInit = ${lastStatusASG}"
							echo "Scale down ASG critical-on-init ${asgCriticalOnInit}"
								aws autoscaling update-auto-scaling-group \
								--auto-scaling-group-name ${asgCriticalOnInit} \
								--no-new-instances-protected-from-scale-in \
								--region ${awsRegion}		
							"""
							break
						} else {
							echo "INFO - Waiting status scaling activity on ${asgCriticalOnInit} to ready"
							sleep(5)
						}
						break
					}

					while(true) {
						lastStatusASG = sh(returnStdout: true, script: """
							set +x
							aws autoscaling describe-scaling-activities \
							--auto-scaling-group-name ${asgCriticalOnInit} \
							--region ${awsRegion} \
							--output yaml \
							--no-cli-pager | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
						""").trim()

						if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
							echo "Scaling activity completed or none in progress for ASG: ${asgCriticalOnInit}"

							while(true) {
								tryScaleDown = sh(returnStdout: true, script: """
									set +x
									aws autoscaling set-desired-capacity \
									--auto-scaling-group-name ${asgCriticalOnInit} \
									--desired-capacity ${defaultDesiredCapacityASG} \
									--region ${awsRegion} || echo "ScalingActivityInProgress"
								""").trim()

								sleep(3)

								if (tryScaleDown.contains("ScalingActivityInProgress")) {
									echo "Scaling activity is still in progress. Retrying in 10 seconds..."
									sleep(10) 
								} else {
									echo "Scaling successful, exiting loop."
								}
								break
							}
						} else {
							echo "INFO - Waiting status scaling activity on ${asgCriticalOnInit} to ready"
							sleep(5)
						}
						break
					}

					while(true) {
						currentProtectedInstancesInASG = sh(returnStdout: true, script: """
							set +x
							aws autoscaling describe-auto-scaling-groups \
							--auto-scaling-group-name ${asgCriticalOnInit} \
							--region ${awsRegion} \
							--output yaml | yq '.AutoScalingGroups[].Instances[] | select(.ProtectedFromScaleIn == true) | .InstanceId' 
						""").trim()

						countProtectedInstancesInASG = sh(returnStdout: true, script: """
							set +x
							aws autoscaling describe-auto-scaling-groups \
							--auto-scaling-group-name ${asgCriticalOnInit} \
							--region ${awsRegion} \
							--output yaml | yq '[.AutoScalingGroups[].Instances[] | select(.ProtectedFromScaleIn == true)] | length'
						""").trim()

						if (countProtectedInstancesInASG == "") {
							echo "No protected instance in ASG ${asgCriticalOnInit}"
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
										--auto-scaling-group-name ${asgCriticalOnInit} \
										--no-protected-from-scale-in \
										--region ${awsRegion}
									"""
									sleep 3
								}
								break
							} else {
								echo "No protected instance in ASG ${asgCriticalOnInit}"
								break
							}
						}
						break
					}
					
					sleep(60)

					while(true) {
						lastStatusASG = sh( returnStdout: true, script: """
						set +x
						aws autoscaling describe-scaling-activities \
						--auto-scaling-group-name ${asgCriticalOnInit} \
						--region ${awsRegion} \
						--output yaml \
						--no-cli-pager | yq '.Activities | sort_by(.StartTime) | reverse | .[0] | .StatusCode'
						""").trim()

						if(lastStatusASG == "Successful" || lastStatusASG == "" || lastStatusASG == "null" || lastStatusASG == null) {
							echo "asgCriticalOnInit = ${asgCriticalOnInit}"

							sh """
								set +x
								echo "Increase ASG critical-on-init ${asgCriticalOnInit}"
								aws autoscaling update-auto-scaling-group \
								--auto-scaling-group-name ${asgCriticalOnInit} \
								--max-size ${defaultMaxSizeCapacityASG} \
								--region ${awsRegion}
							"""
							break
						} else {
							echo "INFO - Waiting status scaling activity on ${asgCriticalOnInit} to ready"
							sleep(5)
						}
						break
					}
				}
			}

			stage('Rollout Restart Statefulset') {
				if(params.skipRolloutSts) {
					echo "Skip Rollout Statefulset"
				} else {
					hasPVCs = sh( returnStdout: true, script: """
						kubectl get sts -A -o yaml | grep -v "justice-play" | yq '.items[] | select(.spec.volumeClaimTemplates[].metadata.name != null) | .metadata.namespace + "---" + .metadata.name + "---" + .status.replicas'
					""").trim().split("\n")
					hasNoPVCs = sh( returnStdout: true, script: """
						kubectl get sts -A -o yaml | grep -v "justice-play" | yq '.items[] | select(.spec.volumeClaimTemplates[].metadata.name == null) | .metadata.namespace + "---" + .metadata.name'
					""").trim().split("\n")

					if (hasPVCs) {
						for (hasPVC in hasPVCs) {
							def (namespaceSts, stsName, replicasSts) = hasPVC.split('---')
							sh """
								kubectl -n ${namespaceSts} scale sts ${stsName} --replicas=0
							"""
						}
					}

					if (hasNoPVCs) {
						for (hasNoPVC in hasNoPVCs) {
							def (namespaceSts, stsName) = hasNoPVC.split('---')
							sh """
								kubectl -n ${namespaceSts} rollout restart sts ${stsName}
							"""
						}
					}

					if (hasPVCs) {
						for (hasPVC in hasPVCs) {
							def (namespaceSts, stsName, replicasSts) = hasPVC.split('---')
							stsReadyReplicas = sh(returnStdout: true, script: """
								kubectl -n ${namespaceSts} get sts ${stsName} -oyaml | yq '.status.readyReplicas'
							""").trim()

							sleep(10)

							if(stsReadyReplicas == null || stsReadyReplicas == "null" || stsReadyReplicas == "0") {
								sh "kubectl -n ${namespaceSts} scale sts ${stsName} --replicas=${replicasSts}"
							} else {
								echo "WARN - Please scale up manually 'kubectl -n ${namespaceSts} scale sts ${stsName} --replicas=${replicasSts}'"
							}
						}
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

					echo "Rollout Restart Emissary services"
					sh "kubectl -n emissary rollout restart deployment"
				}
			}

			stage('Rollout Restart Justice Deployment') {
				if(params.skipRolloutJusticeDeployment) {
					echo "Skip Rollout Justice services"
				} else {
					echo "Rollout Restart Justice Deployment"

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

					sleep(120)
				}
			}

			stage('Rollout Restart Remaining Deployment') {
				if(params.skipRolloutRemainingDeployment) {
					echo "Skip Rollout Remaining Deployment (except justice|karpenter|flux|emissary)"
				} else {
					def userInput = input(
						id: 'userInput', 
						message: 'Rollout Restart Remaining Deployment (except justice|karpenter|flux|emissary)', 
						parameters: [
							[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
						]
					)

					if(!userInput) {
						echo "Skip Rollout Remaining Deployment (except justice|karpenter|flux|emissary)"
					}
					echo "Rollout restart all deployments"
					allNamespaces = sh( returnStdout: true, script: """
						kubectl get namespaces --no-headers -o custom-columns="NAMESPACE:.metadata.name" | grep -Ev 'justice|karpenter|flux|emissary'
					""").trim().split('\n')

					for (namespace in allNamespaces) {			
						sh """
							kubectl -n ${namespace} rollout restart deployment
						"""
					}

					sleep(120)
				}
			}

			stage('Rollout Restart justice-play') {
				if(params.skipRolloutArmadaServices) {
					echo "Skip Rollout Armada Services services"
				} else {
					echo "Rollout restart justice-play (armada services)"

					isConsulServerExists = sh( returnStdout: true, script: """
						kubectl -n justice-play get sts consul-server || echo "NotFound"
					""").trim()

					isNomadServerExists = sh( returnStdout: true, script: """
						kubectl -n justice-play get sts nomad-server || echo "NotFound"
					""").trim()

					if(isConsulServerExists == "NotFound" && isNomadServerExists == "NotFound") {
						echo "Not found Consul Server and Nomad Server. Skipping"
					} else {
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
			}
		}
	}
}