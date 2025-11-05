import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: '1.28', name: 'minEksVersion'),
			string(defaultValue: '1.30', name: 'targetEksVersion'),
			string(defaultValue: '0.31.5', name: 'minKarpenterVersion'),
			booleanParam(defaultValue: false, name: 'skipPreCheck'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String minEksVersion = params.minEksVersion
String targetEksVersion = params.targetEksVersion
String minKarpenterVersion = params.minKarpenterVersion
String skipPreCheck = params.skipPreCheck
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String eksClusterVersionLine
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-eks-upgrade-${targetEksVersion}"

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

			stage('Pre-check') {
				if (params.skipPreCheck) {
					echo "Skip pre-check"
				} else {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("eks") {
								eksClusterVersionLine = sh( returnStdout: true, script: """
									grep -n eks_cluster_version terragrunt.hcl | awk {'print \$1'} | tr -d ':'
								""").trim()
								currentEksVersion =  sh( returnStdout: true, script: """
									aws eks describe-cluster \
									--name "${targetEnvironmentName}" \
									--region "${awsRegion}" \
									--query "cluster.version" \
									--output yaml | cut -d'"' -f2 
								""").trim()
							}
						}
						if (currentEksVersion == minEksVersion) {
							echo "Minimum EKS version is matched"
						} else {
							echo "Minumum EKS not matched"
							exit 1
						}

						awsNodeTemplateLists = sh( returnStdout: true, script: """
							kubectl get awsnodetemplate -oyaml | yq '.items[] | .metadata.name'
						""").trim()
						def awsNodeTemplates = awsNodeTemplateLists.split('\n')
						for (awsNodeTemplate in awsNodeTemplates) {
							echo "Checking awsnodetemplate ${awsNodeTemplate}"
							sh """
								set +x
								kubectl describe awsnodetemplate ${awsNodeTemplate} | grep "Security Group Selector" -A 1 > awsnodetemplate-${awsNodeTemplate}.txt

								cat awsnodetemplate-${awsNodeTemplate}.txt
							"""

							checkSGSpace = sh( returnStdout: true, script: """
								set +x
								grep Ids awsnodetemplate-${awsNodeTemplate}.txt | cut -d':' -f2 | xargs
							""").trim()

							if (checkSGSpace.contains(" ")) {
								echo "awsnodetemplate ${awsNodeTemplate} contains spaces"
								echo "Please edit and remove the spaces"
								echo "kubectl edit awsnodetemplate ${awsNodeTemplate}"
								exit 1
							} else {
								echo "Security group not contains any spaces, continue..."
							}
						}

						awsProvisionerLists = sh( returnStdout: true, script: """
							kubectl get provisioner -o yaml | yq '.items[] | select(.spec.provider.securityGroupSelector["aws-ids"] != null) | .metadata.name'
						""").trim()
						def awsProvisioners = awsProvisionerLists.split('\n')
						echo "Checking provisioner"
						for (awsProvisioner in awsProvisioners) {
							echo "Checking provisioner ${awsProvisioner}"
							sh """
								set +x
								kubectl get provisioner ${awsProvisioner} -oyaml | yq .spec.provider.securityGroupSelector > provisioner-${awsProvisioner}.txt

								cat provisioner-${awsProvisioner}.txt
							"""

							checkProvisionerSpace = sh( returnStdout: true, script: """
								set +x
								grep ids provisioner-${awsProvisioner}.txt | cut -d':' -f2 | xargs
							""").trim()

							if (checkProvisionerSpace.contains(" ")) {
									echo "provisioner ${awsProvisioner} contains spaces"
									echo "Please edit and remove the spaces"
									echo "kubectl edit provisioner ${awsProvisioner}"
									exit 1
								} else {
									echo "Security group not contains any spaces, continue..."
								}
						}

						currentKarpenterVersion = sh( returnStdout: true, script: """
							set +x
							kubectl -n flux-system get ks karpenter -oyaml | yq .spec.path | cut -d'/' -f 5 
						""").trim()

						def parseVersion = { version ->
								def versionParts = version.replace("v", "").split("\\.")
								return [
										major: versionParts[0].toInteger(),
										minor: versionParts[1].toInteger(),
										patch: versionParts[2].toInteger()
								]
						}

						def currentVer = parseVersion(currentKarpenterVersion)
						def targetVer = parseVersion(minKarpenterVersion)
						
						def compareVersions = { curr, targ ->
								if (curr.major != targ.major) {
										return curr.major > targ.major ? 1 : -1
								} else if (curr.minor != targ.minor) {
										return curr.minor > targ.minor ? 1 : -1
								} else if (curr.patch != targ.patch) {
										return curr.patch > targ.patch ? 1 : -1
								}
								return 0
						}

						def comparisonResult = compareVersions(currentVer, targetVer)

						if (comparisonResult > 0) {
							echo "Current Karpenter version is newer than ${minKarpenterVersion}"
						} else if (comparisonResult < 0) {
							echo "Current Karpenter version is older than ${minKarpenterVersion}"
							exit 1
						} else {
							echo "Current Karpenter version is equal to ${minKarpenterVersion}"
						}
					}
				}
			}

			stage("Prepare Upgrade"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("sso_roles") {
							sh """
								terragrunt apply --auto-approve
							"""
						}
					}
				}
			}

			stage("Upgrade EKS to 1.29"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("eks") {
							sh """
								sed -i "${eksClusterVersionLine}s|1.28|1.29|g" terragrunt.hcl
								cat terragrunt.hcl | grep eks_cluster_version
								terragrunt plan -out=eks129
							"""
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
							sh """
								terragrunt apply 'eks129'
							"""
						}
					}
				}
			}

			stage("Upgrade EKS to 1.30"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("ami") {
							if(fileExists("eks_arm64_ami")) {
								dir("eks_arm64_ami") {
									sh """
										sed -i 's|${minEksVersion}|${targetEksVersion}|g' terragrunt.hcl
										grep -r ${targetEksVersion} terragrunt.hcl
										terragrunt init
										terragrunt apply --auto-approve
									"""
								}
							}

							if(fileExists("eks_amd_ami")) {
								dir("eks_amd_ami") {
									sh """
										sed -i 's|${minEksVersion}|${targetEksVersion}|g' terragrunt.hcl
										grep -r ${targetEksVersion} terragrunt.hcl
										terragrunt init
										terragrunt apply --auto-approve
									"""
								}
							}
						}
						
						dir("eks") {
							sh """
								sed -i "${eksClusterVersionLine}s|1.29|1.30|g" terragrunt.hcl
								cat terragrunt.hcl | grep eks_cluster_version
								terragrunt plan -out=eks130
							"""
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

							sh """
								terragrunt apply 'eks130'
							"""
						}
					}
				}
			}

			stage('Commit and Push IAC Repo'){
				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-eks-upgrade-${targetEksVersion}-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						ls -la iacTemp/${envDirectory}
						rm -rf iac/${envDirectory}/ami

						cp -R iacTemp/${envDirectory}/eks/terragrunt.hcl iac/${envDirectory}/eks/terragrunt.hcl
						cp -R iacTemp/${envDirectory}/ami iac/${envDirectory}

						cd iac
						chmod -R 644 ${envDirectory}/eks || true
						chmod -R 644 ${envDirectory}/ami || true

						git checkout -b ${BB_BRANCH_NAME}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${envDirectory}/eks ${envDirectory}/ami
						git commit -m "feat: EKS Upgrade ${targetEksVersion}"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
				}
			}

			stage("Create PR IAC Repo") {
				prSummary="""
:: EKS Upgrade ${targetEksVersion} ${targetEnvironmentName} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: EKS Upgrade ${targetEksVersion} ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME}"
							]
						],
						reviewers: [
								[
										uuid: "{ede31de1-392c-42cc-bf10-f5e45d112a0f}" // Mochammad Rifky Satria Perdana
								],
								[
										uuid: "{6cee0dcd-3d6e-4ef1-8cd0-4ca217ed32d2}" // Adin Baskoro Pratomo
								],
								[
										uuid: "{f115f536-48bf-42f0-9634-30f53f03ed13}" // Adi Purnomo
								],
								[
										uuid: "{8f4086ef-41e9-4eb3-80c0-84212c1c7594}" // Fahmi Maghrizal Mochtar
								],
								[
										uuid: "{e7b16827-0453-4ab8-b70e-2ec781beb07f}" // Rasyiq Farandi
								],
								[
										uuid: "{3bc5a80a-bb63-40a5-829c-82cbde04c2a3}" // Radian Satria Panigas
								],
								[
										uuid: "{b0089c2d-a507-4211-bc6f-e23cd1735f7a}" // Muhamad Ar Ghifary
								],
								[
										uuid: "{4c57253e-0370-446c-8824-ee350e24b4df}" // Robbie Zhang
								],
								[
										uuid: "{a60f808f-4034-49da-89f3-4daf9a2367b6}" // Husni Bakri
								],
								[
										uuid: "{6fcceadc-6463-4a17-9af0-c942f2f43e06}" // Aditya Novian Firdaus
								]
						],
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
	}
}