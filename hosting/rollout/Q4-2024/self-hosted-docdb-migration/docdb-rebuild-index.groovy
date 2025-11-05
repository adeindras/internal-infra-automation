import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
	[
		parameters([
			string(defaultValue: '', name: 'targetEnvironmentName'),
			string(defaultValue: 'justice-shared', name: 'serviceGroup')
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
String serviceGroupSSMModified
serviceGroupSSMModified = serviceGroup.replace("-", "_")
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-rebuild-index"

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
						chmod -R 777 iac || true
						rm -rf ~/.aws/config || true
					"""
				}
			}
			
			stage('Set aws credentials'){
				def (customer, project, environment) = targetEnvironmentName.split('-')
				dir('iac') {
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
				ssmPath = sh(
					returnStdout: true,
					script: "kubectl -n justice get cm cluster-variables -oyaml | yq .data.SSM_PATH").trim()
				mongoIP = sh(
					returnStdout: true,
					script: "kubectl -n mongodb get po mongodb-0 -oyaml  | yq .status.podIP").trim()
				mongoPassword = sh(
					returnStdout: true,
					script: """
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
						kubectl -n tools exec -i ${jumpBoxPod} -- mongo \
							--host=\"${mongoIP}:27017\" \
							--username root \
							--password \"${mongoPassword}\" \
							--eval \"db.getMongo().getDBNames()\" | tail -n +6 | grep -v admin | grep -v local | jq -r '[.[] | select(. != "admin" and . != "local" and . != "config")] | .[]'
					""").trim()
				serviceGroupName = sh(
					returnStdout: true,
					script: "cat iac/${envDirectory}/docdb/${serviceGroup}/terragrunt.hcl | grep \"service_group\" | awk {'print \$3'} | jq -r .").trim()
				docDBAddress = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/${serviceGroupSSMModified}_address\" \
						--with-decryption | jq '.Parameter.Value'
					""").trim()
				docDBUsername = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/${serviceGroupSSMModified}_username\" \
						--with-decryption | jq '.Parameter.Value'
					""").trim()
				docDBPassword = sh(
					returnStdout: true,
					script: """
						aws ssm get-parameter \
						--region ${awsRegion} \
						--name \"${ssmPath}/mongo/${serviceGroupSSMModified}_password\" \
						--with-decryption | jq '.Parameter.Value'
					""").trim()

				switch (null) {
					case ssmPath:
						currentBuild.result = 'FAILURE'
						break
					case mongoIP:
						currentBuild.result = 'FAILURE'
						break
					case mongoPassword:
						currentBuild.result = 'FAILURE'
						break
					case jumpBoxPod:
						currentBuild.result = 'FAILURE'
						break
					case listDatabases:
						currentBuild.result = 'FAILURE'
						break
					case serviceGroupName:
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
						echo "mongoIP = ${mongoIP}"
						// echo "mongoPassword = ${mongoPassword}"
						echo "jumpBoxPod = ${jumpBoxPod}"
						echo "databases = ${listDatabases}"
						echo "serviceGroupName = ${serviceGroupName}"
						echo "docDBAddress = ${docDBAddress}"
						echo "docDBUsername = ${docDBUsername}"
						// echo "docDBPassword = ${docDBPassword}"
						break
				}
			}

			stage('Get Index collection (MongoDB)') {
				def databases = listDatabases.split('\n')
				for (database in databases) {
					rawCommandOutput = sh(
						returnStdout: true,
						script: """
							set +x
							listDBCollection=\$(kubectl -n tools exec -i ${jumpBoxPod} -- mongo --host=\"${mongoIP}:27017\" --username root --password \"${mongoPassword}\" --eval \"db.getSiblingDB('${database}').getCollectionNames();\" | tail -n +6 | jq -r @csv)
							kubectl -n tools exec -i ${jumpBoxPod} -- bash -c "mongo --host=\"${mongoIP}:27017\" --username root --password \"${mongoPassword}\" << 'EOF'
use ${database};
var collections = [ \${listDBCollection} ];
collections.forEach(function(collectionName) {
		var indexes = db.getCollection(collectionName).getIndexes();
		indexes.forEach(function(index) {
				delete index.v; delete index.ns;
				var key = index.key; delete index.key;
				var options = {};
				for (var option in index) {
						options[option] = index[option];
				}
				print('db.getCollection(""xxx'+ collectionName +'xxx"").createIndex(' + tojson(key) + ', ' + tojson(options) + ');');
		});
});
EOF"
						""").trim()
					writeFile(file: "raw-cmd-${database}.txt", text: rawCommandOutput)
					def cmdReindex = readFile("raw-cmd-${database}.txt")
					def updateCmdReindex = cmdReindex.split("\n")[6..-2].join("\n").replace("xxx", "\"")
					writeFile(file: "cmd-reindex-${database}.txt", text: updateCmdReindex)
					sh "cat cmd-reindex-${database}.txt"
				}
			}

			stage('Build index collection (DocDB)') {
				def databases = listDatabases.split('\n')
				for (database in databases) {
					sh "echo \"${database}\""
					rawCommandOutput = sh(
						returnStdout: true,
						script: """
							set +x
							reIndexCommand=\$(cat cmd-reindex-${database}.txt)
							kubectl -n tools exec -i ${jumpBoxPod} -- bash -c "mongo --host=${docDBAddress} --username ${docDBUsername} --password "${docDBPassword}" << 'EOF'
use ${database};
\${reIndexCommand}
EOF"
						""").trim()
					}
			}
		}
	}
}