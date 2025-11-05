import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
  [
    parameters([
      string(defaultValue: '', name: 'targetEnvironmentName'),
      string(defaultValue: 'justice-shared', name: 'elasticacheTgDirectory'),
      string(defaultValue: 'Reconfigure Elasticache Cluster', name: 'maintenanceActivity'),
      string(defaultValue: '120', name: 'maintenanceDuration'),
			string(defaultValue: 'Some services will be unavailable during maintenance', name: 'maintenanceImpact'),
			string(defaultValue: 'Reconfigure Elasticache Cluster to enable multi_az, automatic_failover, at_rest_encryption', name: 'maintenanceDescription')
    ])
  ]
)

String targetEnvironmentName = params.targetEnvironmentName
String elasticacheTgDirectory = params.elasticacheTgDirectory
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String elasticacheClusterDir
String elasticacheClusterId
String elasticacheClusterPrimaryId
String snapshotBackupName
String timeStamp=currentBuild.startTimeInMillis 
String suffix="enc"
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME
String maintenanceActivity = params.maintenanceActivity
String maintenanceDuration = params.maintenanceDuration
String maintenanceImpact = params.maintenanceImpact
String maintenanceDescription = params.maintenanceDescription
String slackGroupHosting = "S044ZF5HPFF"
String slackGroupLiveOps = "S01SPJ0130U"
String slackGroupQaInfra = "S067JL6JT2T"
String slackChannelTest = "C04NV2TKJD6" //#test-channel-for-me
String slackChannelReportInfraChanges = "C017L2M1C3D" // #report-infra-changes
String cc = "<!subteam^${slackGroupHosting}> <!subteam^${slackGroupLiveOps}> <!subteam^${slackGroupQaInfra}>"
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-elasticache-${elasticacheTgDirectory}"

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
						rm -rf iacTemp || true
						cp -R iac iacTemp || true
						chmod -R 777 iacTemp || true
					"""
				}
			}

			stage('Clone deployments repository') {
				sshagent(['bitbucket-repo-read-only']) {
					// Clone deployments repo
					environmentDir = sh(
						returnStdout: true, 
						script: """
							echo ${targetEnvironmentName} | sed 's/-/\\//g'
						""").trim()

					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/deployments.git"
						rm -rf deploymentsTemp || true
						cp -R deployments deploymentsTemp || true
						chmod -R 777 deploymentsTemp || true
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

			stage("Getting elasticache info"){
				dir ("iacTemp/${envDirectory}/elasticache") {
					elasticacheClusterDir = sh(returnStdout: true, script: "ls | grep ^${elasticacheTgDirectory}\$ || true").trim()

					if (elasticacheClusterDir == ""){
						currentBuild.result = 'FAILURE'
					}

					echo elasticacheClusterDir
					
					dir (elasticacheClusterDir) {
						sh "rm -rf .terraform.lock.hcl"
						sh "terragrunt show -json > tgshowoutput.json"
						elasticacheClusterId= sh(
							returnStdout: true,
							script: "cat tgshowoutput.json | jq -r '.values.outputs.id.value'").trim()
						elasticacheClusterPrimaryId= sh(
							returnStdout: true,
							script: "cat tgshowoutput.json | jq -r '.values.root_module.child_modules[].resources[1].values.member_clusters[0]'").trim()
						elasticacheClusterInternalDns= sh(
							returnStdout: true,
							script: "cat tgshowoutput.json | jq -r '.values.outputs.dns_record.value'").trim() // Full DNS
						elasticacheInternalDNS= sh(
							returnStdout: true,
							script: "cat tgshowoutput.json | jq -r '.values.outputs.dns_record.value' | awk -F'.internal' '{print \$1}' | sed 's|\"||g'").trim()
						route53InternalDNS= sh(
							returnStdout: true,
							script: "cat tgshowoutput.json | jq -r '.values.outputs.dns_record.value' | awk -F'internal' '{print \".internal\" \$2}'").trim()
						serviceGroupName= sh(
							returnStdout: true,
							script: "cat terragrunt.hcl | grep service_group | awk {'print \$3'} | sed 's/\"//g'").trim()
					}
					
					switch (null) {
						case elasticacheClusterId:
							currentBuild.result = 'FAILURE'
							break
						case elasticacheClusterPrimaryId:
							currentBuild.result = 'FAILURE'
							break
						case elasticacheClusterInternalDns:
							currentBuild.result = 'FAILURE'
							break
						case route53InternalDNS:
							currentBuild.result = 'FAILURE'
							break
						case elasticacheInternalDNS:
							currentBuild.result = 'FAILURE'
							break
						case serviceGroupName:
							currentBuild.result = 'FAILURE'
							break
						default:
							echo "elasticacheClusterId = ${elasticacheClusterId}"
							echo "elasticacheClusterPrimaryId = ${elasticacheClusterPrimaryId}"
							echo "elasticacheClusterInternalDns = ${elasticacheClusterInternalDns}"
							echo "route53InternalDNS = ${route53InternalDNS}"
							echo "elasticacheInternalDNS = ${elasticacheInternalDNS}"
							echo "serviceGroupName = ${serviceGroupName}"
							break
					}
				}
			}

			stage('Block access to elasticache cluster') {
				def userInput = input(
					id: 'userInput', 
					message: 'This action is disruption, could trigger false positive alert. Ensure you already post in the #report-infra-changes and tag respective team', 
					parameters: [
						[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
					]
				)

				if(!userInput) {
					error "Build failed not confirmed"
				}
				securityGroupID= sh( returnStdout: true, script: """
					aws ec2 describe-security-groups --region ${awsRegion} \
					--filters Name=group-name,Values=${elasticacheClusterId} | jq -r '.SecurityGroups[].GroupId'
					"""
				).trim()
				switch ("") {
					case securityGroupID:
						currentBuild.result = 'FAILURE'
						break
					default:
						echo "securityGroupID: ${securityGroupID}"
						break
				}
				sh """
					ingressRules=\$(aws ec2 describe-security-groups --group-ids ${securityGroupID} --region ${awsRegion} --query "SecurityGroups[0].IpPermissions" --output json)
					
					modifiedIngressRules=\$(aws ec2 describe-security-groups --group-ids ${securityGroupID} --region ${awsRegion} --query "SecurityGroups[0].IpPermissions" --output json | sed 's|6379|0|g' | jq .)

					echo "Revoke security group"
					aws ec2 revoke-security-group-ingress --group-id "${securityGroupID}" --region ${awsRegion} --ip-permissions "\${ingressRules}" --no-cli-pager

					echo "Authorize security group"
					aws ec2 authorize-security-group-ingress --group-id "${securityGroupID}" --region ${awsRegion} --ip-permissions "\${modifiedIngressRules}" --no-cli-pager
				"""
			}
			
			stage('Create elasticache snapshot from primary'){
				snapshotBackupName = "${elasticacheClusterId}-${suffix}-${timeStamp}"
				sh"""
					aws elasticache create-snapshot \
					--cache-cluster-id $elasticacheClusterPrimaryId \
					--region $awsRegion \
					--snapshot-name $snapshotBackupName \
					| jq . -r
				"""
			
				while (true) {
					statusBackup = sh(returnStdout: true, script: """
							aws elasticache describe-snapshots --region $awsRegion  \
							--snapshot-name $snapshotBackupName \
							--query "Snapshots[0].SnapshotStatus" --output text
						""").trim()

					echo "Status Backup File: ${statusBackup}"

					if (statusBackup == "available"){
						break
					}
				}
			}

			stage('Create and modify new elasticache cluster'){
				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-${suffix}-${timeStamp}"
				if ( snapshotBackupName == null ){
					currentBuild.result = 'FAILURE'
				}

				dir ("iacTemp/${envDirectory}/elasticache") {
					sh """
						mkdir "\${elasticacheTgDirectory}-${suffix}"
						cp "\${elasticacheTgDirectory}/terragrunt.hcl" "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"

						echo "Update module v0.39.0 to v0.39.0-1"
						sed -i 's/v0.39.0/v0.39.0-1/g' "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"

						echo "Adjust compability inputs to hcledit"
						sed -i "s/inputs = {/inputs {/g" "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"

						echo "Set default at_rest_encryption_enabled as true"
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute rm "inputs.at_rest_encryption_enabled" -u

						echo "Set default multi_az_enabled as true"
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute rm "inputs.multi_az_enabled" -u

						echo "Set default automatic_failover_enabled as true"
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute rm "inputs.automatic_failover_enabled" -u

						echo "Set cluster size to 2"
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute append "inputs.cluster_size" "2" -u || true
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute set "inputs.cluster_size" "2" -u || true

						lineServiceGroupName=\$(grep -n "service_group" "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" | awk {'print \$1'} | sed 's|:||g')
						echo "Set service group name to ${serviceGroupName}-${suffix}"
						sed -i "\${lineServiceGroupName}s|${serviceGroupName}|${serviceGroupName}-${suffix}|g" "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"
						
						echo "Set new DNS to ${elasticacheInternalDNS}-${suffix}"
						sed -i "s|${elasticacheInternalDNS}|${elasticacheInternalDNS}-${suffix}|g" "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"

						echo "Add Replication Group ID to ${elasticacheClusterId}"
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute append "inputs.replication_group_id" '\"${elasticacheClusterId}-${suffix}\"' -u || true 
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute set "inputs.replication_group_id" '\"${elasticacheClusterId}-${suffix}\"' -u  || true

						echo "Adjust snapshot name ${elasticacheClusterId}-${suffix}"
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute append "inputs.snapshot_name" '\"${snapshotBackupName}\"' -u || true
						hcledit -f "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl" attribute set "inputs.snapshot_name" '\"${snapshotBackupName}\"' -u  || true

						echo "Revert input"
						sed -i "s/inputs {/inputs = {/g" "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"

						echo "Check result"
						cat "\${elasticacheTgDirectory}-${suffix}/terragrunt.hcl"
					"""
				}
			}

			stage('Commit and push iac repo'){
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						ls -la iacTemp/${envDirectory}/elasticache
						cp -R iacTemp/${envDirectory}/elasticache/${elasticacheTgDirectory}-${suffix} iac/${envDirectory}/elasticache/${elasticacheTgDirectory}-${suffix} 
						cd iac
						chmod -R 644 ${envDirectory}/elasticache/${elasticacheTgDirectory}-${suffix} || true
						git checkout -b ${BB_BRANCH_NAME}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${envDirectory}/elasticache/${elasticacheTgDirectory}-${suffix}
						git commit -m "feat: ${BB_BRANCH_NAME}"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
				}
			}

			stage("Create PR iac repo") {
				prSummary="""
:: Elasticache Migration \n \n
:: Enable Multi-AZ \n \n
:: Enable Automatic Failover \n \n
:: Enable At Rest Encryption \n \n
:: Set Cluster Size to 2 (to support Multi-AZ and Automatic Failover) \n \n
:: Set Replication Group ID to ${elasticacheClusterId}-${suffix} \n \n
:: Set new internal domain to ${elasticacheInternalDNS}-${suffix} \n \n
:: Restored from ${snapshotBackupName} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: provision elasticache-${suffix} ${targetEnvironmentName}",
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

			stage('Switch services in the deployments repo') {
				sh "cd deploymentsTemp/${environmentDir}"
				redisAddressSSMPath= sh(
					returnStdout: true,
					script: "cat \$(grep -rl \"redis/*.*address\" deploymentsTemp/${environmentDir} | grep lobby  | tail -n 1) | grep \"redis/*.*address\" | tail -n 1 | awk -F'redis/' '{print \$2}'").trim()
				redisPortIntSSMPath= sh(
					returnStdout: true,
					script: "echo ${redisAddressSSMPath} | sed 's/address/port_int/g'").trim()
				redisPortStrSSMPath= sh(
					returnStdout: true,
					script: "echo ${redisAddressSSMPath} | sed 's/address/port_str/g'").trim()
				echo "redisAddressSSMPath: ${redisAddressSSMPath}"
				echo "redisPortIntSSMPath: ${redisPortIntSSMPath}"
				echo "redisPortStrSSMPath: ${redisPortStrSSMPath}"
				sh"""
					pwd
					cd deploymentsTemp/${environmentDir}
					if [[ \$(cat cluster-information.env | grep EKS_CLUSTER_NAME)  != *${targetEnvironmentName}* ]]; then
						exit 1
					fi
					echo ${environmentDir}
					pwd
					ls -lah .
					
					for i in \$(grep -rl REDIS_HOST . ); do
						echo \$i
						sed -i 's|REDIS_HOST: ".*"|REDIS_HOST: "${elasticacheInternalDNS}-${suffix}${route53InternalDNS}"|g' \$i
						cat \$i | grep REDIS_HOST
					done

					echo "Replace redis/ ssm path to ${serviceGroupName}_${suffix}_address"
					for j in \$(grep -rl "redis/" . | grep secret); do
						echo \$j
						sed -i 's|redis/${redisAddressSSMPath}|redis/${serviceGroupName}_${suffix}_address|g' \$j
						sed -i 's|redis/${redisPortIntSSMPath}|redis/${serviceGroupName}_${suffix}_port_int|g' \$j
						sed -i 's|redis/${redisPortStrSSMPath}|redis/${serviceGroupName}_${suffix}_port_str|g' \$j || true
					done

					echo "Replace value APP_REDIS_ADDRESS"
					for k in \$(grep -rl APP_REDIS_ADDRESS . | grep -v secretKey || true); do
						echo \$k
						sed -i 's|APP_REDIS_ADDRESS: ".*"|APP_REDIS_ADDRESS: "redis://${elasticacheInternalDNS}-${suffix}${route53InternalDNS}:6379"|g' \$k || true
						cat \$k | grep APP_REDIS_ADDRESS || true
					done

					echo "Replace value REDIS_ADDRESS"
					for l in \$(grep -r REDIS_ADDRESS . | grep -v APP_REDIS_ADDRESS | grep -v secretKey  | grep 6379 || true); do
						echo \$l
						sed -i 's|REDIS_ADDRESS: ".*"|REDIS_ADDRESS: "${elasticacheInternalDNS}-${suffix}${route53InternalDNS}:6379"|g' \$l || true
						cat \$l | grep REDIS_ADDRESS || true
					done

					echo "See the changes"
					for m in \$(grep -rl "redis/" . ); do
						echo \$m
						cat \$m | grep redis/
					done
				"""
			}

			stage('Commit and push deployments repo'){
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

						for i in \$(grep -rl REDIS_HOST deploymentsTemp/${environmentDir} | awk -F'${environmentDir}/' '{print \$2}'); do
							echo \$i
							cp -rf deploymentsTemp/${environmentDir}/\$i deployments/${environmentDir}/\$i
						done

						for j in \$(grep -rl "redis/${serviceGroupName}_${suffix}" deploymentsTemp/${environmentDir} | awk -F'${environmentDir}/' '{print \$2}'); do
							echo \$j
							cp -rf deploymentsTemp/${environmentDir}/\$j deployments/${environmentDir}/\$j
						done

						echo "See the changes deployments/${environmentDir}"
						for m in \$(grep -rl "redis/${serviceGroupName}_${suffix}" deployments/${environmentDir} | awk -F'${environmentDir}/' '{print \$2}'); do
							echo \$m
							cat deployments/${environmentDir}/\$m | grep redis/
						done

						cd deployments
						git checkout -b ${BB_BRANCH_NAME}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						chmod -R 644 ${environmentDir} || true
						git add ${environmentDir}
						git commit -m "feat: ${BB_BRANCH_NAME}"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
				}
			}

			stage("Create PR deployments repo") {
				prSummary="""
:: Switch Services to new Elasticache Cluster \n \n
:: Update Redis Host to ${elasticacheInternalDNS}-${suffix}${route53InternalDNS} \n \n
:: Update SSM Path ${redisAddressSSMPath} to ${serviceGroupName}_${suffix}_address \n \n
:: Update SSM Path ${redisPortIntSSMPath} to ${serviceGroupName}_${suffix}_port_int \n \n
:: Update SSM Path ${redisPortStrSSMPath} to ${serviceGroupName}_${suffix}_port_str \n \n
:: See impacted services at tab File changed
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests").openConnection();
					def postData =  [
						title: "feat: switch elasticache services ${targetEnvironmentName}",
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

			stage('Send to #report-infra-changes') {
				withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
					def post = new URL("https://slack.com/api/chat.postMessage").openConnection()
					def postData = [
						channel: "${slackChannelReportInfraChanges}",
						blocks: [
							[
								type: "section",
								text: [
									type: "mrkdwn",
									text: ":robot_face: [${targetEnvironmentName}] Hosting Platform Bot :robot_face:"
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Maintenance:*\n ${maintenanceActivity}"
									]
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Environment:*\n ${targetEnvironmentName}"
									]
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Desc:*\n ${maintenanceDescription}"
									]
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Impact:*\n ${maintenanceImpact}"
									]
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										// @hosting-team @liveops-team @qa-infra-provisioning 
										text: "*cc:* ${cc}"
									]
								]
							]
						]
					]
					def jsonPayload = JsonOutput.toJson(postData)
					post.setRequestMethod("POST")
					post.setDoOutput(true)
					post.setRequestProperty("Content-Type", "application/json")
					post.setRequestProperty("Authorization", "Bearer ${slackToken}")
					post.getOutputStream().write(jsonPayload.getBytes("UTF-8"))

					def postRC = post.getResponseCode()
					println(postRC)
					if (postRC == 200 || postRC == 201) {
						def jsonSlurper = new JsonSlurper()
						def reply = post.getInputStream().getText()
						def replyMap = jsonSlurper.parseText(reply)
						slackThread = replyMap.ts
					}
				}
			}
		}
	}
}