import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String targetEnvironmentName = params.targetEnvironmentName
String elasticacheTgDirectory = params.elasticacheTgDirectory // justice-shared-enc
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String BB_BRANCH_NAME
String suffix="enc"
String suffixDeprecated="deprecated"
String tempDir="temp$BUILD_NUMBER"
String timeStamp=currentBuild.startTimeInMillis
String tfLockRemoteDigest
String tfLockLocalDigest
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-${elasticacheTgDirectory}"

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
						rm -rf iacTemp || true
						cp -R iac iacTemp || true
						chmod -R 777 iacTemp || true
					"""
				}
			}
			
			stage('Set aws credentials'){
				def (customer, project, environment) = targetEnvironmentName.split('-')
				dir('iacTemp') {
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

			stage("Getting elasticache info"){
				dir ("iacTemp/${envDirectory}/elasticache") {
					// Remove suffix -enc
					elasticacheDefault = sh(returnStdout: true, script: "echo ${elasticacheTgDirectory} | sed 's|-${suffix}||g'").trim()
					elasticacheDefaultDir = sh(returnStdout: true, script: "ls | grep ^${elasticacheDefault}\$ || true").trim()
					elasticacheClusterDir = sh(returnStdout: true, script: "ls | grep ^${elasticacheTgDirectory}\$ || true").trim()

					switch ("") {
						case elasticacheDefaultDir:
							currentBuild.result = 'FAILURE'
							echo "elasticacheDefaultDir = ${elasticacheDefaultDir}"
							break
						case elasticacheClusterDir:
							echo "elasticacheClusterDir = ${elasticacheClusterDir}"
							currentBuild.result = 'FAILURE'
							break
						default:
							echo "elasticacheDefaultDir = ${elasticacheDefaultDir}"
							echo "elasticacheClusterDir = ${elasticacheClusterDir}"
							break
					}
					
					dir (elasticacheClusterDir) { // prefix -enc
						sh "terragrunt init"
						sh "terragrunt show -json > tgshowoutput.json"
						elasticacheClusterId= sh(returnStdout: true, script: """
							cat tgshowoutput.json | jq -r '.values.outputs.id.value'
						""").trim()
						elasticacheClusterPrimaryId= sh(returnStdout: true, script: """
							cat tgshowoutput.json | jq -r '.values.root_module.child_modules[].resources[1].values.member_clusters[0]'
						""").trim()
						elasticacheClusterInternalDns= sh(returnStdout: true, script: """
							cat tgshowoutput.json | jq -r '.values.outputs.dns_record.value'
						""").trim() // Full DNS
						elasticacheInternalDNS= sh(returnStdout: true, script: """
							cat tgshowoutput.json | jq -r '.values.outputs.dns_record.value' | awk -F'.internal' '{print \$1}' | sed 's|\"||g'
						""").trim()
						route53InternalDNS= sh(returnStdout: true, script: """
							cat tgshowoutput.json | jq -r '.values.outputs.dns_record.value' | awk -F'internal' '{print \".internal\" \$2}'
						""").trim()
						serviceGroupName= sh(returnStdout: true, script: """
							cat terragrunt.hcl | grep service_group | awk {'print \$3'} | sed 's/\"//g'
						""").trim()
						tgBackend= sh(returnStdout: true, script: """
							pwd | awk -F'${awsAccountId}' '{print ${awsAccountId} \$2 \"/terraform.tfstate\"}'
						""").trim()
						tgBackendPath= sh(returnStdout: true, script: """
							grep -rl \"${tgBackend}\" .terragrunt-cache | grep backend.tf
						""").trim()
						s3BucketRemote= sh(returnStdout: true, script: """
							cat \"${tgBackendPath}\" | grep bucket | awk {'print \$3'} | sed 's|\"||g'
						""").trim()
						s3ElasticacheKey= sh(returnStdout: true, script: """
							cat \"${tgBackendPath}\" | grep key | awk {'print \$3'} | sed 's|\"||g'
						""").trim()
						s3ElasticacheTgUri = "${s3BucketRemote}/${s3ElasticacheKey}"
						sh "rm -rf .terragrunt-cache"
						sh "rm -rf .terraform.lock.hcl"
						switch ("") {
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
							case tgBackend:
								currentBuild.result = 'FAILURE'
								break
							case tgBackendPath:
								currentBuild.result = 'FAILURE'
								break
							case s3BucketRemote:
								currentBuild.result = 'FAILURE'
								break
							case s3ElasticacheKey:
								currentBuild.result = 'FAILURE'
								break
							case s3ElasticacheTgUri:
								currentBuild.result = 'FAILURE'
								break
							default:
								echo "elasticacheClusterId = ${elasticacheClusterId}"
								echo "elasticacheClusterPrimaryId = ${elasticacheClusterPrimaryId}"
								echo "elasticacheClusterInternalDns = ${elasticacheClusterInternalDns}"
								echo "route53InternalDNS = ${route53InternalDNS}"
								echo "elasticacheInternalDNS = ${elasticacheInternalDNS}"
								echo "serviceGroupName = ${serviceGroupName}"
								echo "tgBackend = ${tgBackend}"
								echo "tgBackendPath = ${tgBackendPath}"
								echo "s3BucketRemote = ${s3BucketRemote}"
								echo "s3ElasticacheKey = ${s3ElasticacheKey}"
								echo "s3ElasticacheTgUri = ${s3ElasticacheTgUri}"
								break
						}
					}

					dir (elasticacheDefaultDir) {
						sh "terragrunt init"
						tgBackendElasticacheDefault= sh(
							returnStdout: true,
							script: "pwd | awk -F'${awsAccountId}' '{print ${awsAccountId} \$2 \"/terraform.tfstate\"}'").trim()
						tgBackendPathElasticacheDefault= sh(
							returnStdout: true,
							script: "grep -rl \"${tgBackendElasticacheDefault}\" .terragrunt-cache | grep backend.tf").trim()
						s3ElasticacheKeyElasticacheDefault= sh(
							returnStdout: true,
							script: "cat \"${tgBackendPathElasticacheDefault}\" | grep key | awk {'print \$3'} | sed 's|\"||g'").trim()
						s3ElasticacheUriElasticacheDefault = "${s3BucketRemote}/${s3ElasticacheKeyElasticacheDefault}"
						sh "rm -rf .terragrunt-cache"
						sh "rm -rf .terraform.lock.hcl"
						
						switch ("") {
							case tgBackendElasticacheDefault:
								currentBuild.result = 'FAILURE'
								break
							case tgBackendPathElasticacheDefault:
								currentBuild.result = 'FAILURE'
								break
							case s3BucketRemote:
								currentBuild.result = 'FAILURE'
								break
							case s3ElasticacheKeyElasticacheDefault:
								currentBuild.result = 'FAILURE'
								break
							case s3ElasticacheTgUri:
								currentBuild.result = 'FAILURE'
								break
							default:
								echo "tgBackendElasticacheDefault = ${tgBackendElasticacheDefault}"
								echo "tgBackendPathElasticacheDefault = ${tgBackendPathElasticacheDefault}"
								echo "s3BucketRemote = ${s3BucketRemote}"
								echo "s3ElasticacheKeyElasticacheDefault = ${s3ElasticacheKeyElasticacheDefault}"
								echo "s3ElasticacheUriElasticacheDefault = ${s3ElasticacheUriElasticacheDefault}"
								break
						}
					}
				}
			}

			stage("Deprecating ${elasticacheDefaultDir}") {
				echo "Move terragrunt.hcl and terraformstate ${elasticacheDefaultDir} to ${elasticacheDefaultDir}-${suffixDeprecated}"
					// Backup terraform state
					sh"""
						backupUrielasticacheDefaultDir=\$(echo ${s3ElasticacheUriElasticacheDefault} | awk -F'terraform.tfstate' '{ print \$1 "1backup/terraform.tfstate" \$2}')
						echo "Backup into \${backupUrielasticacheDefaultDir} before permanently move"
						aws s3 cp "s3://${s3ElasticacheUriElasticacheDefault}" "s3://\${backupUrielasticacheDefaultDir}"
					"""
				dir ("iacTemp/${envDirectory}/elasticache") {
					sh"""
						s3ElasticacheDefaultDeprecated=\$(echo ${s3ElasticacheUriElasticacheDefault} | sed 's|${elasticacheDefault}|${elasticacheDefault}-${suffixDeprecated}|g')
						echo "Copy tfstate to \$s3ElasticacheDefaultDeprecated"
						aws s3 cp "s3://${s3ElasticacheUriElasticacheDefault}" "s3://\${s3ElasticacheDefaultDeprecated}"
						
						echo "Move folder to ${elasticacheDefaultDir}-${suffixDeprecated}"
						mv ${elasticacheDefaultDir} ${elasticacheDefaultDir}-${suffixDeprecated}

						cd ${elasticacheDefaultDir}-${suffixDeprecated}
						cat terragrunt.hcl
						terragrunt init
						terragrunt plan
						terragrunt state list
					"""
				}
			}

			stage("Migrate ${elasticacheTgDirectory} to ${elasticacheDefaultDir}") {
				// Backup terraform state
				sh"""
					backupUrielasticacheTgDir=\$(echo ${s3ElasticacheTgUri} | awk -F'terraform.tfstate' '{ print \$1 "backup/terraform.tfstate" \$2}')
					echo "Backup into \${backupUrielasticacheTgDir} before permanently move"
					aws s3 cp "s3://${s3ElasticacheTgUri}" "s3://\${backupUrielasticacheTgDir}"
				"""
				dir ("iacTemp/${envDirectory}/elasticache/") {
					sh"""
						mv ${elasticacheTgDirectory} ${elasticacheDefault}
						cat ${elasticacheDefault}/terragrunt.hcl

						elasticacheS3DefaultUri=\$(echo ${s3ElasticacheTgUri} | sed 's|${elasticacheTgDirectory}|${elasticacheDefault}|g')
						aws s3 cp "s3://${s3ElasticacheTgUri}" "s3://\${elasticacheS3DefaultUri}"
						cd ${elasticacheDefault}
						rm -rf .terragrunt-cache
						rm -rf .terraform.lock.hcl
					"""
				}
			}

			stage("Patch terraformlock Digest in the DynamoDB") {
				dir ("iacTemp/${envDirectory}/elasticache/${elasticacheDefault}") {
					sh "terragrunt plan 2> tgplan.txt || true"
					tfLockLocalDigest= sh(returnStdout: true, script: """
						cat tgplan.txt | grep -oE '[a-f0-9]{32}' | head -n 1
					""").trim()
					tfLockRemoteDigest= sh(returnStdout: true, script: """
						aws dynamodb get-item \
						--region ${awsRegion} \
						--table-name terraform-locks \
						--key '{\"LockID\": {\"S\": \"${s3ElasticacheUriElasticacheDefault}-md5\"}}' --no-cli-pager | jq .Item.Digest.S
					""").trim()
					switch (null) {
						case tfLockLocalDigest:
							currentBuild.result = 'FAILURE'
							break
						case tfLockRemoteDigest:
							currentBuild.result = 'FAILURE'
							break
						default:
							echo "tfLockLocalDigest = ${tfLockLocalDigest}"
							echo "tfLockRemoteDigest = ${tfLockRemoteDigest}"
							break
					}
					sh"""
						if [[ "${tfLockRemoteDigest}" == "${tfLockLocalDigest}" ]]; then
							true
						else
							aws dynamodb update-item \
							--table-name terraform-locks \
							--region ${awsRegion} \
							--key '{\"LockID\": {\"S\": \"${s3ElasticacheUriElasticacheDefault}-md5\"}}' \
							--update-expression \"SET Digest = :digest\" \
							--expression-attribute-values '{\":digest\": {\"S\": \"${tfLockLocalDigest}\" }}'
						fi
						
						terragrunt init
						terragrunt plan
						terragrunt state list

						rm -rf tgplan.txt
						rm -rf tgshowoutput.json
					"""
				}
			}

			stage('Commit and push iac repo'){
				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-elasticache-post-migration-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						ls -la iacTemp/${envDirectory}/elasticache

						rm -rf iac/${envDirectory}/elasticache/${elasticacheDefaultDir}
						rm -rf iac/${envDirectory}/elasticache/${elasticacheTgDirectory}

						mkdir -p iac/${envDirectory}/elasticache/${elasticacheDefaultDir}
						mkdir -p iac/${envDirectory}/elasticache/${elasticacheDefaultDir}-${suffixDeprecated}

						cp iacTemp/${envDirectory}/elasticache/${elasticacheDefaultDir}/terragrunt.hcl iac/${envDirectory}/elasticache/${elasticacheDefaultDir}/terragrunt.hcl
						cp iacTemp/${envDirectory}/elasticache/${elasticacheDefaultDir}-${suffixDeprecated}/terragrunt.hcl iac/${envDirectory}/elasticache/${elasticacheDefaultDir}-${suffixDeprecated}/terragrunt.hcl
						cd iac
						git checkout -b ${BB_BRANCH_NAME}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						chmod -R 644 ${envDirectory}/elasticache/ || true
						git add ${envDirectory}/elasticache/
						git commit -m "feat: ${BB_BRANCH_NAME}"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
				}
			}

			stage("Create PR iac repo") {
				prSummary="""
:: Elasticache Post Migration \n \n
:: Deprecated ${elasticacheDefault} to ${elasticacheDefault}-deprecated \n \n
:: Swap the contents (terragrunt.hcl and tfstate) ${elasticacheClusterDir} to ${elasticacheDefault} \n \n
:: Update DIGEST ${s3ElasticacheUriElasticacheDefault}-md5 on table terraform-lock \n \n
:: Patch DIGEST ${tfLockRemoteDigest} to ${tfLockLocalDigest} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "chore: manage elasticache ${targetEnvironmentName}",
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