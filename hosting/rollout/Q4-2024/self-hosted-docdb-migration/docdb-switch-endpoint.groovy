import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: 'justice-shared', name: 'serviceGroup'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String serviceGroup = params.serviceGroup
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME_IAC_REPO
String BB_BRANCH_NAME_DEPLOYMENTS_REPO
String serviceGroupSSMModified
serviceGroupSSMModified = serviceGroup.replace("-", "_")
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-switch-endpoint"

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
					mainfestClusterDirectory = sh(returnStdout: true, script: """
						find manifests -path "*/${customer}/${project}/${awsRegion}/${environment}"
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

			stage('Generate Kubeconfig and prepare common variable') {
				sh """#!/bin/bash
					set -e
					set -o pipefail
					envsubst < ~/.aws/config.template > ~/.aws/config
					aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
				"""

				ssmPath = sh(
						returnStdout: true,
						script: "kubectl -n justice get cm cluster-variables -oyaml | yq .data.SSM_PATH").trim()
				docDBAddress = sh(
						returnStdout: true,
						script: """
							aws ssm get-parameter \
							--region ${awsRegion} \
							--name ${ssmPath}/mongo/${serviceGroupSSMModified}_address \
							--with-decryption | jq '.Parameter.Value'
						""").trim()
				docDBUsername = sh(
						returnStdout: true,
						script: """
							aws ssm get-parameter \
							--region ${awsRegion} \
							--name ${ssmPath}/mongo/${serviceGroupSSMModified}_username \
							--with-decryption | jq '.Parameter.Value'
							""").trim()
				docDBPassword = sh(
						returnStdout: true,
						script: """
							aws ssm get-parameter \
							--region ${awsRegion} \
							--name ${ssmPath}/mongo/${serviceGroupSSMModified}_password \
							--with-decryption | jq '.Parameter.Value'
						""").trim()
				
				switch (null) {
					case ssmPath:
						currentBuild.result = 'FAILURE'
						break
					case docDBAddress:
						currentBuild.result = 'FAILURE'
						break
					case docDBUsername:
						currentBuild.result = 'FAILURE'
						break
					case docDBPassword:
						currentBuild.result = 'FAILURE'
						break
					default:
						echo "ssmPath = ${ssmPath}"
						echo "docDBAddress = ${docDBAddress}"
						echo "docDBUsername = ${docDBUsername}"
						// echo "docDBPassword = ${docDBPassword}"
						break
				}
			}

			stage("Preparing iac repo"){
				dir("iacTemp") {
					dir("${envDirectory}") {
						docdbDir = sh(returnStdout: true, script: "ls . | grep docdb").trim()
						if (docdbDir == "docdb"){
							sh "echo \"docdb folder available. continue..\""
						} else {
							sh "docdb folder unavailable, ensure the the PR is merged"
							sh "exit 1"
						}
					}

					dir("${mainfestClusterDirectory}") {
						sh """
							mongoUsernamePath=\$(grep -rl "MONGO_USERNAME" . | grep "cluster-secrets")
							for i in \${mongoUsernamePath}; do
								echo \$i
								mongoUsernameSSM=\$(cat \$i | grep "mongo" | grep "username" | awk -F'/' '{print \$2}')
								sed -i "s|\${mongoUsernameSSM}|mongo/${serviceGroupSSMModified}_username|g" \$i
								cat \$i | grep "mongo/${serviceGroupSSMModified}_username"
							done

							mongoPasswordPath=\$(grep -rl "MONGO_PASSWORD" . | grep "cluster-secrets")
							for j in \${mongoPasswordPath}; do
								echo \$j
								mongoPasswordSSM=\$(cat \$j | grep "mongo" | grep "password" | awk -F'/' '{print \$2}')
								sed -i "s|\${mongoPasswordSSM}|mongo/${serviceGroupSSMModified}_password|g" \$j
								cat \$j | grep "mongo/${serviceGroupSSMModified}_password"
							done

							mongodbUrl=\$(grep -rl "mongodb.mongodb" . || true)
							if [[ true ]]; then
								echo "mongodbUrl is empty"
							else
								for k in \${mongodbUrl}; do
									echo \$k
									sed -i 's|mongodb.mongodb|${docDBAddress}|g' \$k
									cat \$k | grep ${docDBAddress}
								done
							fi
						"""
					}
				}
			}

			stage('Commit and push IAC Repo'){
				BB_BRANCH_NAME_IAC_REPO = "jenkins-${awsAccountId}-${targetEnvironmentName}-switch-docdb-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

						for i in \$(grep -rl "MONGO" iacTemp/${mainfestClusterDirectory} | awk -F'${mainfestClusterDirectory}/' '{print \$2}'); do
							echo \$i
							cp -rf iacTemp/${mainfestClusterDirectory}/\$i iac/${mainfestClusterDirectory}/\$i
						done
						
						cd iac
						chmod -R 644 ${mainfestClusterDirectory} || true
						git checkout -b ${BB_BRANCH_NAME_IAC_REPO}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${mainfestClusterDirectory}
						git commit -m "feat: ${BB_BRANCH_NAME_IAC_REPO}"
						git push --set-upstream origin ${BB_BRANCH_NAME_IAC_REPO}
					"""
				}
			}

			stage("Create PR IAC Repo") {
				prSummary="""
:: Switch Endpoint Infra ${targetEnvironmentName} \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: switch endpoint docdb ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME_IAC_REPO}"
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

			stage("Preparing Deployments Repo"){
				environmentDir = sh(
					returnStdout: true, 
					script: """
						echo ${targetEnvironmentName} | sed 's/-/\\//g'
					""").trim()
				dir("deploymentsTemp/${environmentDir}") {				
					sh"""
						for i in \$(grep -r "MONGO" . | grep "ADDRESS"  | awk {'print \$1'} | tr -d ':' | grep -v "configmap"); do
							echo \$i
								mongoAddressOldValue=\$(cat \$i | grep "mongo" | grep "address" | head -n 1 | awk {'print \$2'})
								sed -i "s|\${mongoAddressOldValue}|${ssmPath}/mongo/${serviceGroupSSMModified}_address|g" \$i
							cat \$i | grep \"${ssmPath}/mongo/${serviceGroupSSMModified}_address\" || true
						done

						for j in \$(grep -r "MONGO" . | grep "NAME"  | awk {'print \$1'} | tr -d ':' | grep -v "configmap"); do
							echo \$j
								mongoUsernameOldValue=\$(cat \$j | grep \"mongo\"| grep \"name\" | head -n 1 | awk {'print \$2'})
								sed -i "s|\${mongoUsernameOldValue}|${ssmPath}/mongo/${serviceGroupSSMModified}_username|g" \$j
							cat \$j | grep \"${ssmPath}/mongo/${serviceGroupSSMModified}_username\"
						done

						for k in \$(grep -r "MONGO" . | grep "PASSWORD"  | awk {'print \$1'} | tr -d ':' | grep -v "configmap"); do
							echo \$k
								mongoPasswordOldValue=\$(cat \$k | grep \"mongo\" | grep \"password\" | head -n 1 | awk {'print \$2'})
								sed -i "s|\${mongoPasswordOldValue}|${ssmPath}/mongo/${serviceGroupSSMModified}_password|g" \$k
							cat \$k | grep \"${ssmPath}/mongo/${serviceGroupSSMModified}_password\"
						done

						for l in \$(grep -r "MONGO" . | grep "INT"  | awk {'print \$1'} | tr -d ':' | grep -v "configmap"); do
							echo \$l
								mongoPortIntOldValue=\$(cat \$l | grep \"mongo\" | grep \"int\" | head -n 1 | awk {'print \$2'})
								sed -i "s|\${mongoPortIntOldValue}|${ssmPath}/mongo/${serviceGroupSSMModified}_port_int|g" \$l
							cat \$l | grep \"${ssmPath}/mongo/${serviceGroupSSMModified}_port_int\"
						done

						for m in \$(grep -r "MONGO" . | grep "STR"  | awk {'print \$1'} | tr -d ':' | grep -v "configmap"); do
							echo \$m
								mongoPortIntOldValue=\$(cat \$m | grep \"mongo\" | grep \"str\" | head -n 1 | awk {'print \$2'})
								sed -i "s|\${mongoPortIntOldValue}|${ssmPath}/mongo/${serviceGroupSSMModified}_port_str|g" \$m
							cat \$m | grep \"${ssmPath}/mongo/${serviceGroupSSMModified}_port_str\"
						done
					"""

					sh """
						for n in \$(grep -rl "MONGO_URL" . ); do
							echo \$n
								mongoUrlServiceName=\$(cat \$n | grep "mongo_url" | awk {'print \$2'} | awk -F'/' {'print \$6'} | sed "s|-|_|g")
								mongoUrlOldPath=\$(cat \$n | grep "mongo_url" | awk {'print \$2'})
								mongoUrlNewPath="${ssmPath}/mongo/\${mongoUrlServiceName}_url"
								
								echo "Creating new SSM Parameter \${mongoUrlNewPath} based on \${mongoUrlOldPath} for service \${mongoUrlServiceName}"
								databaseName=\$(aws --region ${awsRegion} ssm get-parameter --name "\${mongoUrlOldPath}" --with-decryption | jq '.Parameter.Value' | awk -F'/' '{print \$4}' | tr -d '\"')
								aws ssm put-parameter \
									--region ${awsRegion} \
									--type SecureString \
									--name "\${mongoUrlNewPath}" \
									--value \"mongodb://${docDBUsername}:${docDBPassword}@${docDBAddress}/\${databaseName}\" \
									--overwrite
								sed -i "s|\${mongoUrlOldPath}|\${mongoUrlNewPath}|g" \$n
							cat \$n | grep \${mongoUrlNewPath}
						done
					"""
				}
			}

			stage('Commit and Push Deployments Repo'){
				BB_BRANCH_NAME_DEPLOYMENTS_REPO = "jenkins-${awsAccountId}-${targetEnvironmentName}-switch-docdb-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

						for i in \$(grep -rl "mongo" deploymentsTemp/${environmentDir} | awk -F'${environmentDir}/' '{print \$2}'); do
							echo \$i
							cp -rf deploymentsTemp/${environmentDir}/\$i deployments/${environmentDir}/\$i
						done

						cd deployments
						git checkout -b ${BB_BRANCH_NAME_DEPLOYMENTS_REPO}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						chmod -R 644 ${environmentDir} || true
						git add ${environmentDir}
						git commit -m "feat: ${BB_BRANCH_NAME_DEPLOYMENTS_REPO}"
						git push --set-upstream origin ${BB_BRANCH_NAME_DEPLOYMENTS_REPO}
					"""
				}
			}

			stage("Create PR Deployments Repo") {
				prSummary="""
:: Switch Services to new DocDB Cluster \n \n
:: See impacted services at tab File changed
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests").openConnection();
					def postData =  [
						title: "feat: switch docdb services ${targetEnvironmentName}",
						source: [
							branch: [
								name: "${BB_BRANCH_NAME_DEPLOYMENTS_REPO}"
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

			stage('Suspending ks mongodb') {
				while (true) {
					fluxSystemKustomizeStatus = sh( returnStdout: true, script: """
						kubectl -n flux-system get ks mongodb -oyaml | k neat | yq '.spec.suspend'
					""").trim()

					if (fluxSystemKustomizeStatus == true) {
						echo "flux-system -n mongodb kustomization is suspend"
						break
					} else {
						sh "kubectl -n flux-system suspend ks mongodb"
					}

					sleep(3)
				}
			}
			
			stage("Scale down mongodb to 0") {
				def userInput = input(
					id: 'userInput', 
					message: 'Before scale down mongodb services, ensure the the migration is completed', 
					parameters: [
						[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
					]
				)

				if(!userInput) {
					error "Build failed not confirmed"
				}

				while(true) {
					mongoDBReplicas = sh(returnStdout: true,
						script: "kubectl -n mongodb get sts mongodb -o yaml | yq '.status.availableReplicas'"
					).trim()

					if (mongoDBReplicas.toInteger() > 0){
						sh """
							kubectl -n mongodb scale sts mongodb --replicas=0
						"""
					}  else if (mongoDBReplicas.toInteger() == 0) {
						echo "mongoDB replicas is 0"
						break
					} else {
						echo "Error not defined, please double check mongodb services in the cluster or scale down manually"
						break
					}
					sleep(30)
				}
			}
			
			stage("Scale up services (using mongo) to 2") {
				externalSecretYaml = sh(
					returnStdout: true,
					script: "kubectl -n justice get externalsecret -oyaml"
				).trim()
				
				writeFile(file: "external-secret-${targetEnvironmentName}.yaml", text: externalSecretYaml)
				sh "ls -lah ."
				
				listsMongoServices = sh(
					returnStdout: true,
					script: """
						yq '.items[] | select(.spec.data[]?.secretKey == "*MONGO*") | .metadata.name' external-secret-${targetEnvironmentName}.yaml | grep -v flux | grep -v job | awk -F'-secret' '{print \$1}'
					""").trim().split('\n')
				
				echo "list impacted services"
				echo "${listsMongoServices}"

				def userInput = input(
					id: 'userInput', 
					message: 'Before scale up the services, ensure the PR merged and ensure the build finished first', 
					parameters: [
						[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
					]
				)

				if(!userInput) {
					def listServices = listsMongoServices.replaceAll("\\[|\\]|,", "")
					echo "Scale up below services manually"
					echo "Patch the pdb min available to 1"
					echo "The minimum replicas is 2"
					echo "list mongo services"
					echo ${listServices}
				}

				sh "kubectl annotate externalsecrets.external-secrets.io -n flux-system --all force-sync=\$(date +%s) --overwrite"
				sh "kubectl annotate externalsecrets.external-secrets.io -n justice --all force-sync=\$(date +%s) --overwrite"
				for (service in listsMongoServices) {
					sh "kubectl -n justice patch pdb ${service} --type='merge' -p '{\"spec\":{\"minAvailable\":1}}' || true"
					sh "kubectl -n justice scale deploy ${service} --replicas=2 || true"
				}
				sleep(30)
			}
		}
	}
}