import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			booleanParam(defaultValue: false, name: 'skipSnapshot'),
		])
	]
)

String targetEnvironmentName = params.targetEnvironmentName
String skipSnapshot = params.skipSnapshot
String dmsTgDirectory = "dms"
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME
String claimRefName="datadir-mongodb-0"
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-dms-clean-up"

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

			stage('Generate Kubeconfig') {
				sh """#!/bin/bash
					set -e
					set -o pipefail
					envsubst < ~/.aws/config.template > ~/.aws/config
					aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
				"""
			}

			stage("Create snapshot vpc mongodb") {
				if (params.skipSnapshot) {
					echo "skip creating snapshot"
				} else {
					specVolumeHandle = sh(returnStdout: true, script: """
						kubectl get pv -o yaml | yq '.items[] | select(.spec.claimRef.name == "${claimRefName}" and .status.phase == "Bound") | .spec.csi.volumeHandle'
					""").trim()

					specAwsEBS = sh(returnStdout: true, script: """
						kubectl get pv -o yaml | yq '.items[] | select(.spec.claimRef.name == "${claimRefName}" and .status.phase == "Bound") | .spec.awsElasticBlockStore.volumeID' | cut -d'/' -f4
					""").trim()

					echo "specVolumeHandle = ${specVolumeHandle}"
					echo "specAwsEBS = ${specAwsEBS}"

					def volumeID

					if (specVolumeHandle != 'null' && specVolumeHandle != '') {
							volumeID = specVolumeHandle
							echo "volumeID = ${volumeID}"
					} else if (specAwsEBS != 'null' && specAwsEBS != '') {
							volumeID = specAwsEBS
							echo "volumeID = ${volumeID}"
					} else {
							currentBuild.result = 'FAILURE'
							echo "Failed to retrieve any volume ID."
					}

					volumeName = claimRefName

					echo "Create snapshot ${volumeName} (${volumeID})"
					snapshotId = sh(returnStdout: true, script: """
						aws ec2 create-snapshot \
						--region ${awsRegion} \
						--volume-id ${volumeID} \
						--description "Manual Snapshot at ${timeStamp} from PVC ${volumeName}" \
						--tag-specifications "ResourceType=snapshot,Tags=[
							{Key=CustomerName,Value=${customer}},
							{Key=EnvironmentName,Value=${environment}},
							{Key=Project,Value=${project}},
							{Key=Namespace,Value=mongodb},
							{Key=PVCName,Value=${volumeName}}
						]" --output yaml | yq '.SnapshotId'
					""").trim()

					while(true) {
						snapshotProgress = sh(returnStdout: true, script: """
							aws ec2 describe-snapshots \
							--region ${awsRegion} \
							--snapshot-ids ${snapshotId} \
							--query "Snapshots[*].{State:State}" \
							--output text \
							--no-cli-pager
						""").trim()

						if (snapshotProgress == "completed") {
							echo "Snapshot ${snapshotId} is available"
							break
						}

						sleep (10)
					}
				}
			}

			stage("Remove DMS access to worker node") {
				securityGroupIdWorkerNode = sh(returnStdout: true, script: """
					aws ec2 describe-security-groups \
					--region ${awsRegion} \
					--filters "Name=tag:Name,Values=${targetEnvironmentName}-eks_worker_sg" \
					--query "SecurityGroups[*].GroupId" \
					--output text \
					--no-cli-pager
					""").trim()
				listCidrBlockIps = sh(returnStdout: true, script: """
					aws ec2 describe-vpcs \
					--region ${awsRegion} \
					--filters "Name=tag:Name,Values=${targetEnvironmentName}" \
					--output json | jq -r '.Vpcs[].CidrBlockAssociationSet[].CidrBlock' | sort | uniq -d
					""").trim()
				
				switch (null) {
					case securityGroupIdWorkerNode:
						currentBuild.result = 'FAILURE'
						break
					case listCidrBlockIps:
						currentBuild.result = 'FAILURE'
						break
					default:
						echo "securityGroupIdWorkerNode = ${securityGroupIdWorkerNode}"
						echo "listCidrBlockIps = ${listCidrBlockIps}"
						break
				}

				def cidrBlockIps = listCidrBlockIps.split('\n')
				for (cidrBlockIp in cidrBlockIps) {
					revokeIngressRuleResponse=sh(returnStdout: true, script: """
						aws ec2 revoke-security-group-ingress \
						--region "${awsRegion}" \
						--group-id "${securityGroupIdWorkerNode}" \
						--protocol tcp \
						--port 0-65535 \
						--cidr "${cidrBlockIp}" \
						--no-cli-pager || echo "NotFound"
						""").trim()
					
					if (revokeIngressRuleResponse == true) {
						echo "Cidr Block ${cidrBlockIp} removed. DMS isolated"
					} else if (revokeIngressRuleResponse == "NotFound" ){
						echo "IngressRules ${revokeIngressRuleResponse}"
					} else {
						echo "Something went wrong ${revokeIngressRuleResponse}"
						exit 1
					}			
				}
			}

			stage("Check current external secret cluster") {
				listExternalSecrets = sh(returnStdout: true, script: """
					kubectl -n justice get externalsecret -o yaml | yq e '.items[] | select(.spec.data[] | .secretKey | test("(?i)mongo")) | select(.metadata.name | test("flux|job") | not) | .metadata.name'
					""").trim()
				def externalSecrets = listExternalSecrets.split('\n')
				for (externalsecret in externalSecrets) {
					echo "Check externalSecrets ${externalsecret}"
					secretKeys = sh(returnStdout: true, script: """
						kubectl -n justice get externalsecret ${externalsecret} -oyaml  | yq '.spec.data[].remoteRef.key' | grep mongo
					""").trim()
					if (secretKeys.contains("mongodb")) {
						echo "Services using old mongodb credentials"
						exit 1
					} else {
						echo "Services using new credentials"
					}
				}
			}

			stage("Remove mongoDB in IAC Repo") {
				dir("iac/${mainfestClusterDirectory}") {
					mongodbYaml = sh(returnStdout: true, script: """
						grep -rl "./manifests/platform/mongodb/" .
					""").trim()
					def extendedPath = "sync/extended"

					switch(null) {
						case mongodbYaml:
							currentBuild.result = 'Failure'
							break
						case extendedPath:
							currentBuild.result = 'Failure'
							break
						default:
							echo "mongodbYaml = ${mongodbYaml}"
							echo "extendedPath = ${extendedPath}"
							break
					}
					sh """
						yq e 'del(.bases[] | select(. == "./mongodb.yaml"))' -i "${extendedPath}/kustomization.yaml"
						rm -rf sync/extended/mongodb.yaml 
					"""
				}
			}

			stage("Scaledown mongoDB at K8S Cluster") {
				def userInput = input(
					id: 'userInput', 
					message: 'Before Scaledown, ensure the the services using new DocDB cluster', 
					parameters: [
						[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
					]
				)

				if(!userInput) {
					error "Build failed not confirmed"
				}
				while(true) {
					def mongodbNamespace = sh(returnStdout: true, script: """
						kubectl get ns mongodb -o jsonpath='{.status.phase}' || echo "NotFound"
					""").trim()

					if (mongodbNamespace == "Active") {
						def currentMongoDBReplicas = sh(returnStdout: true, script: """
							kubectl -n mongodb get sts mongodb -oyaml | yq '.status.replicas' || echo "NotFound"
						""").trim()

						if (currentMongoDBReplicas == "NotFound") {
								echo "Kustomization mongodb removed"
								break
						} else if (currentMongoDBReplicas == "0") {
								echo "Mongodb replicas scaled to 0"
								break
						} else if (currentMongoDBReplicas.toInteger() > 0) {
							sh """
								kubectl -n mongodb scale sts mongodb --replicas=0 || true
								kubectl patch -n flux-system kustomizations.kustomize.toolkit.fluxcd.io mongodb -p '{"spec":{"prune":true}}' --type=merge || true
								kubectl -n mongodb delete sts mongodb || true
								kubectl -n flux-system delete ks mongodb || true
							"""
							sleep(30)
						}
					} else {
							echo "mongodb namespace is removed"
							break
					}
				}
			}

			stage('Commit and push iac repo'){
				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-post-migration-to-docdb-${timeStamp}"
				sshagent(['bitbucket-repo-read-only']) {
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						cd iac
						chmod -R 644 ${mainfestClusterDirectory} || true
						git checkout -b ${BB_BRANCH_NAME}
						git config --global user.email "build@accelbyte.net"
						git config --global user.name "Build AccelByte"
						git status
						git add ${mainfestClusterDirectory}
						git commit -m "feat: ${BB_BRANCH_NAME}"
						git push --set-upstream origin ${BB_BRANCH_NAME}
					"""
				}
			}

			stage("Create PR iac repo") {
				prSummary="""
:: Post Migration self-hosted mongodb to DocDB ${targetEnvironmentName} \n \n
:: Removed self-hosted mongodb resource  \n \n
:: Removed DMS services  \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "chore: post-migration ${targetEnvironmentName} remove unused mongodb",
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

			stage("teardown dms services") {
				dir("iacTemp/${envDirectory}/${dmsTgDirectory}/docdb-migration-justice/dms") {
					sh """
						terragrunt init
						terragrunt destroy --auto-approve
					"""
				}

				dir("iacTemp/${envDirectory}/${dmsTgDirectory}/docdb-migration-justice/ssm") {
					sh """
						terragrunt init
						terragrunt destroy --auto-approve
					"""
				}
			}
		}
	}
}