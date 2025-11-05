import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
	[
		parameters([
			choice(choices: envList, description: "Environment to migrate", name: "targetEnvironmentName"),
			string(defaultValue: '1.30', name: 'minEksVersion'),
			string(defaultValue: '1.32', name: 'targetEksVersion'),
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

def getAddonVersion(eksVersion, addonKey) {
	version = sh(returnStdout: true, script: """
		yq eval ".KUBERNETES_VERSION.${eksVersion}.ADDONS.${addonKey}" eks.yaml
	""").trim()

	if (!version) {
		error ("Error: AddonKey or version not found in Kubernetes version ${eksVersion}. Build Failed.")
	}

	return version
}

String targetEnvironmentName = params.targetEnvironmentName
String minEksVersion = params.minEksVersion
String targetEksVersion = params.targetEksVersion
String eksAddonFolderName
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String oldCoreDnsVersion
String oldKubeProxyVersion
String oldVpcCniVersion
String oldEbsCsiVersion
String newCoreDnsVersion
String newKubeProxyVersion
String newVpcCniVersion
String newEbsCsiVersion
String BB_BRANCH_NAME
def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-eks-addons-upgrade-${targetEksVersion}"

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

			stage('Prepare common variable') {
				dir("iacTemp") {
					dir("${envDirectory}") {
						sh "ls -lah ."
						if(fileExists("eks_addon")) {
							eksAddonFolderName = "eks_addon"
						} else {
							eksAddonFolderName = "eks_addons"
						}
					}
				}

				dir("iacTemp") {
					dir("scripts/eks-helper") {
						oldCoreDnsVersion = getAddonVersion("${minEksVersion}", "COREDNS_VERSION")
						oldKubeProxyVersion = getAddonVersion("${minEksVersion}", "KUBE_PROXY_VERSION")
						oldVpcCniVersion = getAddonVersion("${minEksVersion}", "VPC_CNI_VERSION")
						oldEbsCsiVersion = getAddonVersion("${minEksVersion}", "EBS_CSI_VERSION")

						newCoreDnsVersion = getAddonVersion("${targetEksVersion}", "COREDNS_VERSION")
						newKubeProxyVersion = getAddonVersion("${targetEksVersion}", "KUBE_PROXY_VERSION")
						newVpcCniVersion = getAddonVersion("${targetEksVersion}", "VPC_CNI_VERSION")
						newEbsCsiVersion = getAddonVersion("${targetEksVersion}", "EBS_CSI_VERSION")	
					}
				}

				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-eks-addons-upgrade-${targetEksVersion}-${timeStamp}"
			}

			stage('Create Branch'){
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						echo "BRANCH NAME: ${BB_BRANCH_NAME}"
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						
						cd iac
						git checkout -b ${BB_BRANCH_NAME}
						
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
				}
			}

			stage("Prepare Upgrade"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("sso_roles") {
							sh """
								terragrunt init || true
								terragrunt apply --auto-approve
							"""
						}
					}
				}
			}

			stage("Upgrade EKS Addons") {
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("${eksAddonFolderName}") {
							sh """
								sed -i 's|${oldCoreDnsVersion}|${newCoreDnsVersion}|g' terragrunt.hcl
								sed -i 's|${oldKubeProxyVersion}|${newKubeProxyVersion}|g' terragrunt.hcl
								sed -i 's|${oldVpcCniVersion}|${newVpcCniVersion}|g' terragrunt.hcl
								sed -i 's|${oldEbsCsiVersion}|${newEbsCsiVersion}|g' terragrunt.hcl
								
								terragrunt init || true
								terragrunt plan -out="eksAddon"
				 			"""

							def userInput = input(
									id: 'userInput', 
									message: 'Please review carefully and ensure this does not destroy anything', 
									parameters: [
											[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
									]
							)

							if (!userInput) {
									error "Build failed: User did not confirm."
							}

							sh """
								terragrunt apply "eksAddon"
							"""
						}
					}
				}
			}

			stage('Commit and Push IAC Repo'){
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						ls -la iacTemp/${envDirectory}
						rm -rf iac/${envDirectory}/${eksAddonFolderName}

						cp -R iacTemp/${envDirectory}/${eksAddonFolderName} iac/${envDirectory}

						cd iac
						chmod -R 644 ${envDirectory}/${eksAddonFolderName} || true

						git status
						git add ${envDirectory}/${eksAddonFolderName}
						git commit -m "feat: EKS Addons Upgrade ${targetEksVersion}"
						git push
					"""
				}
			}

			stage("Create PR IAC Repo") {
				prSummary="""
:: EKS Addons Upgrade ${targetEksVersion} ${targetEnvironmentName} \n \n
:: PR Created by ${userId} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: EKS Addons Upgrade ${targetEksVersion} ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME}"
							]
						],
						reviewers: [
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
										uuid: "{c2927dd0-de16-4f0a-a1cb-1c3a7e73b4ef}" // Ade Saputra
								],
								[
										uuid: "{92ee2cd7-8ca6-472f-bba8-2b2d7008867c}" // Wandiatama Wijaya Rahman
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