import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: 'justice-shared', name: 'docdbTgDirectory'),
			string(defaultValue: 'justice-shared', name: 'serviceGroup'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String docdbTgDirectory = params.docdbTgDirectory
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
String docDB = "live/280035340643/randomgames/justice/us-west-2/dev/docdb/justice-shared/terragrunt.hcl"
String dmsPath = "live/280035340643/randomgames/justice/us-west-2/dev/dms"
String additionalSubnets
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-docdb"

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

			stage("Preparing docdb terragrunt.hcl"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						additionalSubnets = sh(returnStdout: true, script: """
							ls . | grep ^additional_subnets || true
						""").trim()
						docdbDir = sh(returnStdout: true, script: """
								ls . | grep ^docdb\$ || echo "NotFound"
						""").trim()

						if (docdbDir == "NotFound") {
								sh "mkdir -p docdb/${docdbTgDirectory}"
								sh "chmod -R 777 docdb/${docdbTgDirectory}"
						} else {
								def checkDocdbTgDirectory = sh(returnStdout: true, script: """
										ls docdb | grep ^${docdbTgDirectory}\$ || echo "NotFound"
								""").trim()

								if (checkDocdbTgDirectory == "NotFound") {
										sh "mkdir -p docdb/${docdbTgDirectory}"
										sh "chmod -R 777 docdb/${docdbTgDirectory}"
								} else {
										echo "Target directory already exists. Exiting."
										sh "exit 1"
								}
						}
					}

					if (additionalSubnets == "additional_subnets") {
						sh"""
							echo ${docDBAdditionalSubnet}
							ls ${docDBAdditionalSubnet} 
							cp ${docDBAdditionalSubnet} ${envDirectory}/docdb/${docdbTgDirectory}/terragrunt.hcl
							cat ${envDirectory}/docdb/${docdbTgDirectory}/terragrunt.hcl
						"""
					} else {
						sh"""
							echo ${docDB}
							ls ${docDB}
							cp ${docDB} ${envDirectory}/docdb/${docdbTgDirectory}/terragrunt.hcl
							cat ${envDirectory}/docdb/${docdbTgDirectory}/terragrunt.hcl
						"""
					}
					
					dir("${envDirectory}/docdb/${docdbTgDirectory}") {
						lineDocDBPassword = sh(returnStdout: true, script: """
							grep -n docdb_password terragrunt.hcl | awk {'print \$1'} | tr -d ':' || echo "NotFound"
						""").trim()
						lineServiceGroup = sh(returnStdout: true, script: """
							grep -n service_group terragrunt.hcl | awk {'print \$1'} | tr -d ':' || echo "NotFound"
						""").trim()
						echo "lineDocDBPassword: ${lineDocDBPassword}"
						echo "lineServiceGroup: ${lineServiceGroup}"

						if (lineDocDBPassword && lineDocDBPassword != 'NotFound') {
								sh "sed -i '${lineDocDBPassword}d' terragrunt.hcl"
						}
						if (lineServiceGroup && lineServiceGroup != 'NotFound') {
								def oldServiceGroupValue = sh(returnStdout: true, script: """
										grep 'service_group' terragrunt.hcl | awk '{print \$3}' | tr -d '\\"'
								""").trim()
								echo "oldServiceGroupValue: ${oldServiceGroupValue}"
								sh """
										sed -i '${lineServiceGroup}s|${oldServiceGroupValue}|${serviceGroup}|g' terragrunt.hcl
								"""
						}

						sh """
							ls -lah .
							tagGame=\$(hcledit -f ../../eks/terragrunt.hcl attribute get "locals.game")
							hcledit -f terragrunt.hcl attribute set "locals.game" \${tagGame} -u
							sed -i 's|v0.25.0|v0.25.1|g' terragrunt.hcl
							hcledit -f terragrunt.hcl fmt -u
							cat terragrunt.hcl
							terragrunt init
							terragrunt validate
						"""
					}
				}
			}

			stage('Commit and push iac repo (DocDB)'){
				BB_BRANCH_NAME_DOCDB = "jenkins-${awsAccountId}-${targetEnvironmentName}-provision-${docdbTgDirectory}-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						ls -la iacTemp/${envDirectory}
						mkdir -p iac/${envDirectory}/docdb/${docdbTgDirectory} || true
						cp -R iacTemp/${envDirectory}/docdb/${docdbTgDirectory} iac/${envDirectory}/docdb/
						cd iac
						chmod -R 644 ${envDirectory}/docdb/${docdbTgDirectory} || true
						git checkout -b ${BB_BRANCH_NAME_DOCDB}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${envDirectory}/docdb/${docdbTgDirectory}
						git commit -m "feat: ${BB_BRANCH_NAME_DOCDB}"
						git push --set-upstream origin ${BB_BRANCH_NAME_DOCDB}
					"""
				}
			}

			stage("Create PR iac repo (DocDB)") {
				prSummary="""
:: Provision DocDB ${targetEnvironmentName} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: provision docdb ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME_DOCDB}"
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