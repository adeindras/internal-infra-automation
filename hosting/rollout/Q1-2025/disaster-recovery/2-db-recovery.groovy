import groovy.json.JsonSlurper
import groovy.json.JsonOutput

properties([
		parameters([
				string(
						name: 'fileUrl',
						defaultValue: 'https://jenkinscd.accelbyte.io/job/hosting/job/Pipelines/view/Infrastructure%20Rollout/job/Infrastructure-Rollout/job/Q1-2025/job/Disaster%20Recovery/job/1-database-scraper/lastSuccessfulBuild/artifact/output.json',
						description: 'Input the URL from the database scraper'
				),
				reactiveChoice(
						choiceType: 'PT_SINGLE_SELECT',
						name: 'targetEnvironmentName',
						referencedParameters: 'fileUrl',
						script: groovyScript(
								fallbackScript: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												return ["Error: Please provide correct url"]
										'''
								],
								script: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												try {
														def url = fileUrl
														def jsonContent = new URL(url).text.trim()
														def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)

														def result = []
														if (jsonData.containsKey('env')) {
																result = [jsonData['env']]
														}

												} catch (Exception e) {
														return ["Error fetching data from: ${fileUrl}", "Exception: ${e.message}"]
												}
										'''
								]
						)
				),
				string(
						name: 'dbDirectory',
						defaultValue: '',
						description: 'Database directory. Use relative path from env folder (i.e rds/justice-shared)'
				),
				reactiveChoice(
						choiceType: 'PT_SINGLE_SELECT',
						name: 'dbType',
						referencedParameters: 'fileUrl',
						script: groovyScript(
								fallbackScript: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												return ["Error: Please provide correct url"]
										'''
								],
								script: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												try {
														def url = fileUrl
														def jsonContent = new URL(url).text.trim()
														def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)

														def parsedData = jsonData.collectEntries { key, value ->
																[key, value instanceof Map ? value.collectEntries { k, v -> [k, v instanceof Map ? new HashMap(v) : v] } : value]
														}

														return parsedData.keySet().findAll { it != 'env' }.toList()
												} catch (Exception e) {
														return ["Error fetching data from: ${fileUrl}", "Exception: ${e.message}"]
												}
										'''
								]
						)
				),
				reactiveChoice(
						choiceType: 'PT_RADIO',
						name: 'dbIdentifier',
						referencedParameters: 'fileUrl,dbType',
						script: groovyScript(
								fallbackScript: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												return ["Error: Unable to process dbType data"]
										'''
								],
								script: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												try {
														def jsonContent = new URL(fileUrl).text.trim()
														def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)
														def parsedData = jsonData.collectEntries { key, value ->
																[key, value instanceof Map ? value.collectEntries { k, v -> [k, v instanceof Map ? new HashMap(v) : v] } : value]
														}

														def selectedDbType = dbType
														def identifiers = parsedData[selectedDbType]?.keySet()?.toList() ?: []
														return identifiers
												} catch (Exception e) {
														return ["Error fetching identifiers: ${e.message}"]
												}
										'''
								]
						)
				),
				reactiveChoice(
						choiceType: 'PT_RADIO',
						name: 'dbSnapshotIdentifier',
						referencedParameters: 'fileUrl,dbType,dbIdentifier',
						script: groovyScript(
								fallbackScript: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												return ["Error: Unable to process snapshot data"]
										'''
								],
								script: [
										classpath: [],
										oldScript: '',
										sandbox: true,
										script: '''
												try {
														def selectedDbType = dbType
											 			def selectedDbIdentifier = dbIdentifier

														def jsonContent = new URL(fileUrl).text.trim()
														def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)
														def parsedData = jsonData.collectEntries { key, value ->
																[key, value instanceof Map ? value.collectEntries { k, v -> [k, v instanceof Map ? new HashMap(v) : v] } : value]
														}

														return parsedData[selectedDbType]?.get(selectedDbIdentifier)?.snapshots ?: []
												} catch (Exception e) {
														return ["Error fetching snapshots: ${e.message}"]
												}
										'''
								]
						)
				)
		])
])

def preFormatHcl(){
	sh """
		sed -i "s/inputs = {/inputs {/g" terragrunt.hcl
	"""
}

def postFormatHcl(){
	sh """
		sed -i "s/inputs {/inputs = {/g" terragrunt.hcl
	"""
}

def configureSnapshot(dbType, dbSnapshotIdentifier, snapshotTag){
	def eksHcl = readFile("terragrunt.hcl")
	if (eksHcl.contains(snapshotTag)) {
		echo "INFO: snapshot identifier set"
		echo "INFO: Replace identifier"
		preFormatHcl()
		sh """
			hcledit -f terragrunt.hcl attribute set "inputs.${snapshotTag}" '"latest"' -u
		"""
		postFormatHcl()
		sh """
			sed -i 's|latest|${dbSnapshotIdentifier}|' terragrunt.hcl
			cat terragrunt.hcl | grep ${snapshotTag} | tail -n 5
		"""
	} else {
		echo "INFO: Need to set snapshot identifier"
		echo "INFO: latest ${dbType} Snapshot is ${dbSnapshotIdentifier}"

		preFormatHcl()
		sh """
			hcledit -f terragrunt.hcl attribute append "inputs.${snapshotTag}" '"latest"' -u
		"""
		postFormatHcl()

		sh """
			sed -i 's|latest|${dbSnapshotIdentifier}|' terragrunt.hcl
			cat terragrunt.hcl | tail -n 5
		"""
	}
}

def confirm(){
	def userInput = input(
		id: 'userInput', 
		message: 'Please review carefully, during terragrunt apply ensure it doesnt break anything', 
		parameters: [
			[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
		]
	)
	
	if(!userInput) {
		error "Build failed not confirmed"
	}
}

def customConfirmation(resources) {
	def userInput = input(
		id: 'userInput', 
		message: 'Please review carefully, to recovery docDB we need to remove existing instances (${resources})', 
		parameters: [
			[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
		]
	)
	
	if(!userInput) {
		error "Build failed not confirmed"
	}
}

def (customer, project, environment) = targetEnvironmentName.split('-')
String targetEnvironmentName = params.targetEnvironmentName
String dbDirectory = params.dbDirectory
String dbType = params.dbType
String dbIdentifier = params.dbIdentifier
String dbSnapshotIdentifier = params.dbSnapshotIdentifier
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
currentBuild.displayName = "#${BUILD_NUMBER}-recovry-${dbIdentifier}"
String elasticacheClusterDir
String tgOutputDbIdentifier
String rdsFinalSnapshot
def docDbInstances

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

			stage("Apply SSO roles") {
				dir("iacTemp/${envDirectory}/sso_roles"){
					sh """
						terragrunt init || true
						terragrunt apply --auto-approve
					"""
				}
			}

			stage("Directory Validation") {
				dir("iacTemp/${envDirectory}") {
					if (fileExists(dbDirectory)) {
						dir(dbDirectory) {
							sh "terragrunt output -json > tgoutput.json"
							def jqQuery = ""
							switch("${dbType}") {
								case "rds":
										jqQuery = ".db_instance_identifier.value"
								break
								case "docdb":
										jqQuery = ".docdb_arn.value | awk -F\':cluster:\' \'{print \$2}\'"
								break
								case "elasticache":
										jqQuery = ".id.value"
								break
							}

							tgOutputDbIdentifier = sh(returnStdout: true, script: """
								cat tgoutput.json | jq -r ${jqQuery}
							""").trim()
						}
					} else {
						echo "Directory ${dbDirectory} not exist"
						error "Directory ${dbDirectory} not exist"
					}

					if (tgOutputDbIdentifier == dbIdentifier) {
						echo "INFO: directory match"
					} else {
						echo "tgOutputDbIdentifier: ${tgOutputDbIdentifier}"
						echo "dbIdentifier: ${dbIdentifier}"
						error "Database Identifier not match"
					}
				}
			}

			stage("Preparation") {
				echo "INFO: Generate access to kubernetes"
				sh """#!/bin/bash
					set -e
					set -o pipefail
					envsubst < ~/.aws/config.template > ~/.aws/config
					aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
				"""

				if (dbType == "rds") {
					rdsFinalSnapshot = sh(returnStdout: true, script: """
						aws rds describe-db-snapshots --region ${awsRegion} --output yaml | yq '
							.DBSnapshots 
							| sort_by(.SnapshotCreateTime) 
							| reverse 
							| .[] 
							| select(.DBInstanceIdentifier == "${dbIdentifier}" and .DBSnapshotIdentifier | contains("final")) 
							| .DBSnapshotIdentifier' | head -n 1
					""").trim()
				}

				if (dbType == "docdb") {
					docDbInstances = sh(returnStdout: true, script: """
						aws docdb describe-db-instances \
						--region ${awsRegion} \
						--output yaml | yq '.DBInstances[] | select(.DBClusterIdentifier == "${dbIdentifier}") | .DBInstanceIdentifier'
					""").trim().split('\n')

					echo "docDbInstances = ${docDbInstances}"
					
					customConfirmation(${docDbInstances})

					for (docDbInstance in docDbInstances) {
						echo "INFO: deleting DocDB Instances ${docDbInstance}"
						sh """
							aws docdb delete-db-instance \
							--db-instance-identifier "${docDbInstance}" \
							--region ${awsRegion}
						"""
					}
				}
			}

			stage("Configure snapshot") {
				dir("iacTemp/${envDirectory}"){
					dir("${dbDirectory}"){
						def snapshotTag = (dbType == "elasticache") ? "snapshot_name" : "snapshot_identifier"
						configureSnapshot(dbType, dbSnapshotIdentifier, snapshotTag)
					}
				}
			}

			stage('Commit and push IAC Repo'){
				BB_BRANCH_NAME_IAC_REPO = "jenkins-${awsAccountId}-${targetEnvironmentName}-recovery-${dbIdentifier}-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

						cp iacTemp/${envDirectory}/${dbDirectory}/terragrunt.hcl iac/${envDirectory}/${dbDirectory}/terragrunt.hcl 
						chmod 644 iac/${envDirectory}/${dbDirectory}/terragrunt.hcl || true

						cd iac
						git checkout -b ${BB_BRANCH_NAME_IAC_REPO}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${envDirectory}/${dbDirectory}/terragrunt.hcl
						git commit -m "fix(recovery): configure snapshot ${dbIdentifier}"
						git push --set-upstream origin ${BB_BRANCH_NAME_IAC_REPO}
					"""
				}
			}

			stage("Plan") {
				dir("iacTemp/${envDirectory}"){
					dir("${dbDirectory}"){
						sh """
							terragrunt init -upgrade
						"""

						if (dbType == "docdb") {
								sh "terragrunt plan -replace='module.documentdb_cluster.aws_docdb_cluster.default[0]' -out=tgplan"
						} else {
								sh "terragrunt plan -out=tgplan"
						}
					}
				}
			}

			stage("Apply") {
				dir("iacTemp/${envDirectory}"){
					dir("${dbDirectory}"){

						confirm()

						if (dbType == "rds" && (rdsFinalSnapshot != "null" || rdsFinalSnapshot != "")) {
							echo "INFO: delete latest final snapshot ${rdsFinalSnapshot}"
								sh """
									aws rds delete-db-snapshot \
									--region ${awsRegion} \
									--db-snapshot-identifier ${rdsFinalSnapshot}
								"""
						}

						if (dbType == "docdb") {
							while(true) {
								countDocDbInstances = sh(returnStdout: true, script: """
									aws docdb describe-db-instances \
									--region ${awsRegion} \
									--output yaml | yq e '.DBInstances | map(select(.DBClusterIdentifier == "${dbIdentifier}")) | length'
								""").trim()

								if (countDocDbInstances == "0") {
									break
								}

								sleep(10)
							}
						}

						sh "terragrunt apply 'tgplan'"
					}
				}
			}

			stage("Restart All Justice Services") {
				sh """
					kubectl -n justice rollout restart deployment
				"""
			}

			stage("Create PR IAC Repo") {
				BB_BRANCH_NAME_IAC_REPO = "jenkins-${awsAccountId}-${targetEnvironmentName}-recovery-${dbIdentifier}-${timeStamp}"
				prSummary="""
				:: Recovery ${dbIdentifier} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "fix(recovery): configure snapshot ${dbIdentifier}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME_IAC_REPO}"
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
