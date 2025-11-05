import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: 'dms', name: 'dmsTgDirectory'),
			string(defaultValue: 'justice-shared', name: 'serviceGroup')
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String dmsTgDirectory = params.dmsTgDirectory
String serviceGroup = params.serviceGroup
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME_DOCDB
String BB_BRANCH_NAME_DMS
String docDBAdditionalSubnet = "live/455912570532/sandbox/justice/us-east-2/dev/docdb/terragrunt.hcl"
String docDB = "live/280035340643/randomgames/justice/us-west-2/dev/docdb/terragrunt.hcl"
String dmsPath = "live/280035340643/randomgames/justice/us-west-2/dev/dms"
String additionalSubnets
String serviceGroupSSMModified
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-dms"

node('hosting-agent') {
	container('tool') {
		dir(tempDir){
			def (customer, project, environment) = targetEnvironmentName.split('-')
			stage('Clone iac repository') {
				sshagent(['bitbucket-repo-read-only']) {
					// Clone IAC repo
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

			stage("Preparing dms"){
				dir("iacTemp") {
					ssmPath = sh(
						returnStdout: true,
						script: "kubectl -n justice get cm cluster-variables -oyaml | yq .data.SSM_PATH").trim()
					mongoIP = sh(
						returnStdout: true,
						script: "kubectl -n mongodb get po mongodb-0 -oyaml  | yq .status.podIP").trim()
					mongoPassword = sh( returnStdout: true, script: """
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
							kubectl -n tools exec -i ${jumpBoxPod} \
							-- mongo --host=\"${mongoIP}:27017\" \
							--username root \
							--password \"${mongoPassword}\" \
							--eval \"db.getMongo().getDBNames()\" | tail -n +6 | jq '[.[] | select(. != "admin" and . != "local" and . != "config")]'
							""").trim()

					if (!ssmPath) {
						currentBuild.result = 'FAILURE'
						echo "ssmPath is missing"
					} else if (!mongoIP) {
						currentBuild.result = 'FAILURE'
						echo "mongoIP is missing"
					} else if (!mongoPassword) {
						currentBuild.result = 'FAILURE'
						echo "mongoPassword is missing"
					} else if (!jumpBoxPod) {
						currentBuild.result = 'FAILURE'
						echo "jumpBoxPod is missing"
					} else if (!listDatabases) {
						currentBuild.result = 'FAILURE'
						echo "listDatabases is missing"
					} else {
						// All required variables are present
						echo "ssmPath = ${ssmPath}"
						echo "mongoIP = ${mongoIP}"
						// echo "mongoPassword = ${mongoPassword}"
						echo "jumpBoxPod = ${jumpBoxPod}"
						echo "databases = ${listDatabases}"
					}

					echo "Checking ${ssmPath}/mongo_username and ${ssmPath}/mongo_password path"
					ssmMongoUsernamePreviousValue = sh(returnStdout: true, script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name "${ssmPath}/mongo_username:1" \
						--with-decryption \
						--output yaml | yq .Parameter.Value
						""").trim()
					ssmMongoPasswordPreviousValue = sh(returnStdout: true, script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name "${ssmPath}/mongo_password:1" \
						--with-decryption \
						--output yaml | yq .Parameter.Value
						""").trim()
					ssmMongoUsernameCurrentValue = sh(returnStdout: true, script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name "${ssmPath}/mongo_username" \
						--with-decryption \
						--output yaml | yq .Parameter.Value
						""").trim()
					ssmMongoPasswordCurrentValue = sh(returnStdout: true, script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name "${ssmPath}/mongo_password" \
						--with-decryption \
						--output yaml | yq .Parameter.Value
						""").trim()

					if (!ssmMongoUsernamePreviousValue) {
						currentBuild.result = 'FAILURE'
						echo "ssmMongoUsernamePreviousValue is missing"
					} else if (!ssmMongoPasswordPreviousValue) {
						currentBuild.result = 'FAILURE'
						echo "ssmMongoPasswordPreviousValue is missing"
					} else if (!ssmMongoUsernameCurrentValue) {
						currentBuild.result = 'FAILURE'
						echo "ssmMongoUsernameCurrentValue is missing"
					} else if (!ssmMongoPasswordCurrentValue) {
						currentBuild.result = 'FAILURE'
						echo "ssmMongoPasswordCurrentValue is missing"
					} else {
						// All required variables are present
						echo "ssmMongoUsernamePreviousValue = ${ssmMongoUsernamePreviousValue}"
						// echo "ssmMongoPasswordPreviousValue = ${ssmMongoPasswordPreviousValue}"
						echo "ssmMongoUsernameCurrentValue = ${ssmMongoUsernameCurrentValue}"
						// echo "ssmMongoPasswordCurrentValue = ${ssmMongoPasswordCurrentValue}"
					}

					if (ssmMongoUsernamePreviousValue == ssmMongoUsernameCurrentValue) {
						echo "${ssmPath}/mongo_username are matched"
					} else {
						echo "Revert value ${ssmPath}/mongo_username"
						sh"""
							aws ssm put-parameter \
							--region "${awsRegion}" \
							--name "${ssmPath}/mongo_username" \
							--value "${ssmMongoUsernamePreviousValue}" \
							--type SecureString \
							--overwrite
						"""
					}
					
					if (ssmMongoPasswordPreviousValue == ssmMongoPasswordCurrentValue) {
						echo "${ssmPath}/mongo_password are matched"
					} else {
						echo "Revert value ${ssmPath}/mongo_password"
						sh"""
							set +x
							aws ssm put-parameter \
							--region "${awsRegion}" \
							--name "${ssmPath}/mongo_password" \
							--value "${ssmMongoPasswordPreviousValue}" \
							--type SecureString \
							--overwrite
						"""
					}

					dir("${envDirectory}/automation-platform"){
						echo "Update IAM Policy for terraform role"
						sh"""
							terragrunt init
							terragrunt plan
							terragrunt apply --auto-approve
						"""
					}
					
					sh """
						echo "copying to ${envDirectory}/${dmsTgDirectory}"
						pwd 
						ls -lah ${dmsPath} 
						cp -R ${dmsPath} ${envDirectory}/${dmsTgDirectory}
						chmod -R 777 ${envDirectory}
						ls -lah ${envDirectory}
					"""

					dir("${envDirectory}/${dmsTgDirectory}/docdb-migration-justice/ssm") {
						serviceGroupSSMModified = serviceGroup.replace("-", "_")
						sh """
							lineServiceGroup=\$(grep -n "service_group" terragrunt.hcl | awk {'print \$1'} | sed 's|:||g')
							serviceGroupValue=\$(cat terragrunt.hcl | grep service_group | awk {'print \$3'} | sed 's|\"||g')
							sed -i "\${lineServiceGroup}s|\${serviceGroupValue}|${serviceGroup}|g" terragrunt.hcl
							
							lineSourcePasswordSSM=\$(grep -n "db_source_password_ssm" terragrunt.hcl | grep ssm_path | awk {'print \$1'} | sed 's|:||g' || true)
							sed -i "\${lineSourcePasswordSSM}s|mongo_password|mongo/mongodb_password|g" terragrunt.hcl || true

							lineTargetPasswordSSM=\$(grep -n "db_target_password_ssm" terragrunt.hcl | grep ssm_path | awk {'print \$1'} | sed 's|:||g' || true)
							sed -i "\${lineTargetPasswordSSM}s|os_password|${serviceGroupSSMModified}_password|g" terragrunt.hcl || true

							terragrunt hclfmt .

							cat terragrunt.hcl

							terragrunt init
							terragrunt plan
							terragrunt apply --auto-approve
						"""
					}

					dir("${envDirectory}/${dmsTgDirectory}/docdb-migration-justice/dms") {
						def databasesHcl = readFile('databases.hcl')
						def updatedHcl = databasesHcl.replaceAll(/databases\s+=\s+\[[^\]]+\]/, "databases = ${listDatabases}")
						writeFile(file: 'databases.hcl', text: updatedHcl)
						
						sh """
							set +x
							lineServiceGroup=\$(grep -n "service_group" terragrunt.hcl | grep -v local | awk {'print \$1'} | sed 's|:||g')
							serviceGroupValue=\$(cat terragrunt.hcl | grep service_group | grep -v local | awk {'print \$3'} | sed 's|\"||g')
							sed -i "\${lineServiceGroup}s|\${serviceGroupValue}|${serviceGroup}|g" terragrunt.hcl
							
							oldDBSourceServer=\$(cat terragrunt.hcl| grep db_source_server | grep -v local | awk {'print \$3'}  | tr -d '"')
							sed -i "s|\${oldDBSourceServer}|${mongoIP}|g" terragrunt.hcl

							lineSourcePasswordSSM=\$(grep -n "/eks/randomgames/justice/dev/mongo/mongodb_password" terragrunt.hcl | awk {'print \$1'} | sed 's|:||g' || true)
							sed -i "\${lineSourcePasswordSSM}s|randomgames/justice/dev|${customer}/justice/${environment}|g" terragrunt.hcl || true

							lineTargetPasswordSSM=\$(grep -n "/eks/randomgames/justice/dev/mongo/os_password" terragrunt.hcl | awk {'print \$1'} | sed 's|:||g' || true)
							sed -i "\${lineTargetPasswordSSM}s|/eks/randomgames/justice/dev/mongo/os_password|/eks/${customer}/justice/${environment}/mongo/${serviceGroupSSMModified}_password|g" terragrunt.hcl || true
							
							lineDBTargetPasswordSSM=\$(grep -n "db_target_password_ssm" terragrunt.hcl | grep ssm_path | awk {'print \$1'} | sed 's|:||g' || true)
							sed -i "\${lineTargetPasswordSSM}s|os_password|mongo/${serviceGroupSSMModified}_password|g" terragrunt.hcl || true

							lineDBTargetPasswordSSM=\$(grep -n "db_target_password_ssm" terragrunt.hcl | grep ssm_path | awk {'print \$1'} | sed 's|:||g' || true)
							sed -i "\${lineDBTargetPasswordSSM}s|os_password|${serviceGroupSSMModified}_password|g" terragrunt.hcl || true
							
							terragrunt hclfmt .
						"""

						sh """
							cat databases.hcl
							cat terragrunt.hcl
						"""
					}
				}
			}

			stage("Modifying secgroup worker node") {
				sh """
					securityGroupIdWorkerNode=\$(aws ec2 describe-security-groups --region ${awsRegion} --filters \"Name=tag:Name,Values=${targetEnvironmentName}-eks_worker_sg\" --query \"SecurityGroups[*].GroupId\" --output text --no-cli-pager) 

					for i in \$(aws ec2 describe-vpcs --region ${awsRegion} --filters \"Name=tag:Name,Values=${targetEnvironmentName}\" --output json | jq -r '.Vpcs[].CidrBlockAssociationSet[].CidrBlock'); 
						do aws ec2 authorize-security-group-ingress \
							--region ${awsRegion} \
							--group-id \${securityGroupIdWorkerNode} \
							--protocol -1 --port all --cidr \$i  --no-cli-pager || true 
					done;
				"""
			}

			stage('Commit and push iac repo (DMS)'){
				BB_BRANCH_NAME_DMS = "jenkins-${awsAccountId}-${targetEnvironmentName}-provision-dms-${dmsTgDirectory}-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						ls -la iacTemp/${envDirectory}
						cp -R iacTemp/${envDirectory}/${dmsTgDirectory} iac/${envDirectory}/${dmsTgDirectory}
						cd iac
						chmod -R 644 ${envDirectory}/${dmsTgDirectory} || true
						git checkout -b ${BB_BRANCH_NAME_DMS}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${envDirectory}/${dmsTgDirectory}
						git commit -m "feat: ${BB_BRANCH_NAME_DMS}"
						git push --set-upstream origin ${BB_BRANCH_NAME_DMS}
					"""
				}
			}

			stage("Create PR iac repo (DMS)") {
				prSummary="""
:: Provision DMS ${targetEnvironmentName} \n \n
:: Migrating Database databases.hcl \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: provision dms ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME_DMS}"
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