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

def updateEks(minEks, targetEks, colorCode) {
		sh """
				sed -i "s|${minEks}|${targetEks}|g" terragrunt.hcl
				grep eks_cluster_version terragrunt.hcl
				terragrunt plan -out="eks${colorCode}"
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
			terragrunt apply "eks${colorCode}"
		"""
}

def updateAmi(folderName, minEks, targetEks) {
	if(fileExists(folderName)) {
		dir(folderName) {
			sh """
				sed -i 's|${minEks}|${targetEks}|g' terragrunt.hcl
				grep -r ${targetEks} terragrunt.hcl
				terragrunt init || true
				terragrunt apply --auto-approve
			"""
		}
	}
}

def updateTextInFile(folderName, eksVersion) {
	if(fileExists(folderName)) {
		echo "Directory ${folderName} is found. Continue.."
		dir(folderName) {
			eks_cluster_version = sh(returnStdout: true, script: """
				hcledit -f terragrunt.hcl attribute get "locals.eks_cluster_version"
			""").trim()

			if (!eks_cluster_version.contains(eksVersion)) {
				echo "Expecting eks_cluster_eversion to ${eksVersion} in terragrunt.hcl but not found"
				echo "Update eks_cluster_version to ${eksVersion}"
				sh """
					hcledit -f terragrunt.hcl attribute set "locals.eks_cluster_version" '"latest"' -u
				"""
				sh """
					sed -i 's|latest|${eksVersion}|' terragrunt.hcl
					cat terragrunt.hcl | grep ${eksVersion}
				"""
			}
		}
	} else {
		echo "Directory ${folderName} is not found. Skipping"
	}
}

String targetEnvironmentName = params.targetEnvironmentName
String minEksVersion = params.minEksVersion
String targetEksVersion = params.targetEksVersion
String skipPreCheck = params.skipPreCheck
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME
def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
def (major, minor) = minEksVersion.tokenize('.').collect { it.toInteger() }
def blueMinorVersion = "${major}.${minor + 1}"
def greenMinorVersion = "${major}.${minor + 2}"
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

			stage('Prepare common variable') {
				def (customer, project, environment) = targetEnvironmentName.split('-')
				
				currentEksVersion =  sh( returnStdout: true, script: """
					aws eks describe-cluster \
					--name "${targetEnvironmentName}" \
					--region "${awsRegion}" \
					--query "cluster.version" \
					--output yaml | cut -d'"' -f2 
				""").trim()

				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("ami") {
							updateTextInFile("eks_arm64_ami", minEksVersion)
							updateTextInFile("eks_amd64_ami", minEksVersion)
						}
					}
				}

				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-eks-upgrade-${targetEksVersion}-${timeStamp}"
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

			stage("Upgrade EKS to ${blueMinorVersion}"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("eks") {
							updateEks(minEksVersion, blueMinorVersion, "Blue")
						}
					}
				}
			}

			stage("Upgrade EKS to ${greenMinorVersion}"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						dir("ami") {
							updateAmi("eks_arm64_ami", minEksVersion, greenMinorVersion)

							updateAmi("eks_amd64_ami", minEksVersion, greenMinorVersion)
						}
						
						dir("eks") {
							updateEks(blueMinorVersion, greenMinorVersion, "Green")
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
						rm -rf iac/${envDirectory}/ami

						cp -R iacTemp/${envDirectory}/eks/terragrunt.hcl iac/${envDirectory}/eks/terragrunt.hcl
						cp -R iacTemp/${envDirectory}/ami iac/${envDirectory}

						cd iac
						chmod -R 644 ${envDirectory}/eks || true
						chmod -R 644 ${envDirectory}/ami || true

						git status
						git add ${envDirectory}/eks ${envDirectory}/ami
						git commit -m "feat: EKS Upgrade ${targetEksVersion}"
						git push
					"""
				}
			}

			stage("Create PR IAC Repo") {
				prSummary="""
:: EKS Upgrade ${targetEksVersion} ${targetEnvironmentName} \n \n
:: PR Created by ${userId} \n \n
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