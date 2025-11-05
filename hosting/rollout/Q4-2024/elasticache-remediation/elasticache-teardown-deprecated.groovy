import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: 'justice-shared-deprecated', name: 'elasticacheTargetDirectory'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String elasticacheTargetDirectory = params.elasticacheTargetDirectory // justice-shared-deprecated
String elasticacheDefaultDirectory = "justice-shared"
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String BB_BRANCH_NAME
String suffix="enc"
String suffixDeprecated="deprecated"
String tempDir="temp$BUILD_NUMBER"
String timeStamp=currentBuild.startTimeInMillis
String elasticacheJusticeSharedDir
String elasticacheJusticeSharedDeprecatedDir
String snapshotName
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-teardown-elasticache-${elasticacheTargetDirectory}"

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

			stage("Check elasticache directory"){
				dir ("iac/${envDirectory}/elasticache") {
					elasticacheJusticeSharedDir = sh(returnStdout: true, script: "ls | grep ^${elasticacheDefaultDirectory}\$").trim()
					elasticacheJusticeSharedDeprecatedDir = sh(returnStdout: true, script: "ls | grep ^${elasticacheTargetDirectory}\$").trim()

					switch ("") {
						case elasticacheJusticeSharedDir:
							currentBuild.result = 'FAILURE'
							break
						case elasticacheJusticeSharedDeprecatedDir:
							currentBuild.result = 'FAILURE'
							break
						default:
							echo "elasticacheJusticeSharedDir = ${elasticacheJusticeSharedDir}"
							echo "elasticacheJusticeSharedDeprecatedDir = ${elasticacheJusticeSharedDeprecatedDir}"
							break
					}

					dir (elasticacheJusticeSharedDir) {
						sh "rm -rf .terragrunt-cache || true"
						sh "rm -rf .terraform.lock.hcl || true"
						sh "terragrunt init"
						sh "terragrunt output -json > tgoutput.json"
						sh "cat tgoutput.json"
						elasticacheJusticeSharedId= sh(
							returnStdout: true,
							script: "cat tgoutput.json | jq .id.value").trim()
						if(elasticacheJusticeSharedId.contains("enc")) {
							echo "Elasticache justice-shared verified"
							sh "rm -rf tgoutput.json"
						} else {
							echo "Please double check, justice-shared folder (ensure post migration steps is merged)"
							exit 1
						}
					}

					dir (elasticacheJusticeSharedDeprecatedDir) {
						sh "rm -rf .terragrunt-cache || true"
						sh "rm -rf .terraform.lock.hcl || true"
						sh "terragrunt init"
						sh "terragrunt output -json > tgoutput.json"
						sh "cat tgoutput.json"
						elasticacheJusticeSharedDeprecatedId= sh(
							returnStdout: true,
							script: "cat tgoutput.json | jq .id.value").trim()
						if(!elasticacheJusticeSharedDeprecatedId.contains("enc")) {
							echo "Elasticache ${elasticacheJusticeSharedDeprecatedId} verified"
							sh "rm -rf tgoutput.json"
						} else {
							echo "Please double check, ${elasticacheJusticeSharedDeprecatedId} folder (ensure post migration steps is merged)"
							exit 1
						}
					}
				}
			}
			stage("Create snapshot elasticache") {
				elasticacheJusticeSharedDeprecatedId001="${elasticacheJusticeSharedDeprecatedId}-001"
				snapshotName="manual-snapshot-${elasticacheJusticeSharedDeprecatedId001}-${timeStamp}"
				sh"""
					aws elasticache create-snapshot \
					--region ${awsRegion} \
					--cache-cluster-id "${elasticacheJusticeSharedDeprecatedId001}" \
					--snapshot-name "${snapshotName}" \
					--output yaml --no-cli-pager
				"""

				while(true) {
					snapshotStatus = sh(returnStdout: true, script: """
						aws elasticache describe-snapshots \
						--region ${awsRegion} \
						--snapshot-name "${snapshotName}" \
						--output yaml --no-cli-pager | yq '.Snapshots[].SnapshotStatus'
					""").trim()	

					if (snapshotStatus == "available") {
						echo "Snapshot is available"
						break
					}
				}
			}

			stage("Teardown ${elasticacheJusticeSharedDeprecatedDir}") {
				def userInput = input(
					id: 'userInput', 
					message: 'Before teardown, ensure the ${elasticacheJusticeSharedDeprecatedDir} is the correct folder', 
					parameters: [
						[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
					]
				)

				if(!userInput) {
					error "Build failed not confirmed"
				}
				dir ("iac/${envDirectory}/elasticache/${elasticacheJusticeSharedDeprecatedDir}") {
					sh """
						terragrunt init
						terragrunt state rm aws_ssm_parameter.redis_address || true
						terragrunt state rm aws_ssm_parameter.redis_address_with_port || true
						terragrunt state rm aws_ssm_parameter.redis_port_int || true
						terragrunt state rm aws_ssm_parameter.redis_port_str || true

						wait
						terragrunt destroy --auto-approve
					"""
				}
			}

			stage('Commit and push iac repo'){
				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-teardown-elasticache-${elasticacheJusticeSharedDeprecatedDir}-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					dir ("iac/${envDirectory}/elasticache") {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						rm -rf ${elasticacheJusticeSharedDeprecatedDir}
						git checkout -b ${BB_BRANCH_NAME}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						chmod -R 644 . || true
						git add .
						git commit -m "feat: ${BB_BRANCH_NAME}"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
					}
				}
			}

			stage("Create PR iac repo") {
				prSummary="""
:: Teardown Unused Elasticache \n \n
:: Teardown elasticache ${elasticacheTargetDirectory} \n \n
:: Snapshot ${snapshotName}
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "chore: teardown deprecated elasticache ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME}"
							]
						],
						reviewers:[],
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