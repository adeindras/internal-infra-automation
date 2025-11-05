import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "Target RDS Cluster to Upgrade to PostgreSQL 16", name: 'rdsClusterIdentifier', defaultValue: "" ),
            string(description: "Target PostgreSQL Version. Please refer to https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_UpgradeDBInstance.PostgreSQL.MajorVersion.html", name: 'postgresqlTargetVersion', defaultValue: "16.1" ),
            string(description: "TG Directory IaC of the Target RDS e.g live/455912570532/sandbox/justice/us-east-2/dev/rds/justice-shared-pg16", name: 'tgDirectory', defaultValue: "" ),
			text(description: "AWS Credentials for running terragrunt apply. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),
			booleanParam(name: 'blockConnection', defaultValue: false, description: 'Block Connection by removing Inbound Rules in Security Group of the RDS Instances'),
			booleanParam(name: 'scaledownServices', defaultValue: false, description: 'Scaledown services that use Postgresql to prevent write. Alternative to Block Connection method.'),
			booleanParam(name: 'skipSnapshotUpgrade', defaultValue: false, description: 'Skip Snapshot Upgrade'),
            booleanParam(name: 'skipPRCreation', defaultValue: false, description: 'Skip Pull Request Creation in Bitbucket')
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
        envData.push("Error getting env list data")
    } else {
        return envData
    }
}

String targetEnvironmentName = params.targetEnvironmentName
String rdsClusterIdentifier = params.rdsClusterIdentifier.trim()
String postgresqlTargetVersion = params.postgresqlTargetVersion.trim()
String tgDirectory = params.tgDirectory.endsWith('/') ? params.tgDirectory[0..-2] : params.tgDirectory
String serviceGroup = tgDirectory.split('/')[-1]
String snapshotId = "${targetEnvironmentName}-pg16-${serviceGroup}"

String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-${serviceGroup}-rds-postgresql-upgrade"

node('hosting-agent') {
	container('tool') {
		dir(tempDir){

			stage('Fetch required manifests and scripts') {
				dir('iac') {
                checkout scmGit(
                    branches: [[name: '*/master']],
                    extensions: [ cloneOption(shallow: true) ],
                    userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                        url: 'git@bitbucket.org:accelbyte/iac.git']])
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

					sh """
						rm -rf ~/.aws/config || true
					"""

					// def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = params.awsCreds.split('\n')
                    def creds = params.awsCreds.split('\n')
                    def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = creds.size() >= 3 ? [creds[0], creds[1], creds[2]] : [creds[0], creds[1], null]
					env.AWS_ACCESS_KEY_ID = awsAccessKeyId.replaceAll('"', '').split('=')[1]
					env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey.replaceAll('"', '').split('=')[1]
                    if (awsSessionToken != null) env.AWS_SESSION_TOKEN = awsSessionToken.replaceAll('"', '').split('=')[1]
					env.AWS_DEFAULT_REGION = awsRegion
					env.AWS_REGION = awsRegion
					sh 'aws sts get-caller-identity --no-cli-pager'

				}
			}

			// stage('Block Connection to RDS Postgresql 12') {

			// 	if (params.blockConnection) {
			// 		dir(tempDir){
			// 			rdsSecurityGroupID = sh(returnStdout: true, script: """
			// 				aws rds describe-db-instances \
			// 					--db-instance-identifier "${rdsClusterIdentifier}" \
			// 					--query 'DBInstances[0].VpcSecurityGroups[0].VpcSecurityGroupId' \
			// 					--output text
			// 			""").trim()
			// 			sh """
			// 				if [ -z "${rdsSecurityGroupID}" ]; then
			// 					echo "Error: Could not find security group for RDS instance ${rdsClusterIdentifier}"
			// 					exit 1
			// 				fi

			// 				#Backup SG
			// 				aws ec2 describe-security-group-rules \
			// 				--filters Name="group-id",Values="${rdsSecurityGroupID}" \
			// 				--output json > sg-backup-${targetEnvironmentName}.json

			// 				aws ec2 describe-security-group-rules \
			// 				 	--filters Name="group-id",Values="${rdsSecurityGroupID}" Name="is-egress",Values="false" \
			// 				 	--query 'SecurityGroupRules[*].SecurityGroupRuleId' \
			// 				 	--output text | xargs -n1 aws ec2 revoke-security-group-rules \
			// 				 	--group-id "${rdsSecurityGroupID}" \
			// 				 	--security-group-rule-ids
							
			// 				echo "${BOLD}${GREEN}All inbound rules have been removed from security group ${rdsSecurityGroupID}${END}"

			// 			"""
			// 			archiveArtifacts '*.json'

			// 		}
			// 	} else {
			// 		echo "Skipping blocking the connection"
			// 	}
			// }

			stage("Scaledown Services") {


				if (params.scaledownServices) {

					timeout(time: 30, unit: 'MINUTES') {
						input(
							id: 'userInput', 
							message: 'Services in justice namespace will be scaled down. This will cause OUTAGE/DOWNTIME. U sure u wanna proceed?', 
						)
					}

					externalSecretYaml = sh(
							returnStdout: true,
							script: "kubectl -n justice get externalsecrets.external-secrets.io -oyaml"
					).trim()

					writeFile(file: "external-secret-${targetEnvironmentName}.yaml", text: externalSecretYaml)
					listsPGServices = sh(
						returnStdout: true,
						script: """
							yq '.items[] | select(.spec.data[]?.remoteRef.key == "*postgre*") | .metadata.name' external-secret-${targetEnvironmentName}.yaml | grep -v flux | grep -v job | awk -F'-secret' '{print \$1}'
						"""
					).trim().split('\n')

					for (service in listsPGServices) {
						sh """
							kubectl -n justice get po | grep ${service}
							kubectl -n justice scale deploy ${service} --replicas=0 || true
						"""
					}

                    sh """
                        kubectl -n justice get deploy | grep 1/1
                        kubectl -n justice get deploy | grep 0/0
                    """

					sh "kubectl -n justice scale deploy analytics-airflow-scheduler --replicas=0 || true"
                    sh "kubectl -n justice scale deploy --all --replicas=0 || true"

					//Waiting for graceful termination
					sleep(60)
				} 
				else {
					echo "Skipping services scaledwon. No outage/downtime."
				}
			}

			stage('Create Snapshot') {

                def exitCode = sh(
                            script: """
                                aws rds describe-db-snapshots \
                                    --db-snapshot-identifier ${snapshotId} \
                                    --query 'DBSnapshots[*].[Status]' \
                                    --output text
                            """,
							returnStatus: true
                        )
				snapshotExists = exitCode == 0
				
				if (snapshotExists) {
					echo "Snapshot exists. Skipping snaphost creation.."
				} else {
					sh """
						aws rds describe-db-instances --db-instance-identifier "${rdsClusterIdentifier}" --query 'DBInstances[*].[DBInstanceIdentifier]' --output text > /dev/null
						if [ \$? -eq 0 ]; then
							echo "RDS instance ${rdsClusterIdentifier} exists."
						else
							echo "RDS instance ${rdsClusterIdentifier} does not exist."
							exit 1
						fi

						#Create snapshot
						echo "Creating snapshot ${snapshotId} for cluster ${rdsClusterIdentifier}..."
						# Create the snapshot
						aws rds create-db-snapshot \
							--db-instance-identifier "${rdsClusterIdentifier}" \
							--db-snapshot-identifier "${snapshotId}"

						# Wait for the snapshot to be available
						# thiw will fail after 60 failed checks
						aws rds wait db-snapshot-available \
							--db-instance-identifier "${rdsClusterIdentifier}" \
							--db-snapshot-identifier "${snapshotId}"
						
						if [ \$? -eq 0 ]; then
							echo "Snapshot ${snapshotId} created successfully!"
							
							# Get snapshot details
							aws rds describe-db-snapshots \
								--db-snapshot-identifier "${snapshotId}" \
								--query 'DBSnapshots[0].{SnapshotID:DBSnapshotIdentifier,Status:Status,Created:SnapshotCreateTime}' \
								--output table 
						else
							echo "Error: Snapshot creation may have failed. Please check AWS console."
						fi
					"""
				}
			}

			stage('Upgrade Snapshot') {
				//To-Do: Check Snapshot version
				if(params.skipSnapshotUpgrade) {
					echo "Skipping snapshot upgrade..."
				} else {
					sh """
						aws rds modify-db-snapshot \
							--db-snapshot-identifier "${snapshotId}" \
							--engine-version ${postgresqlTargetVersion}
							
						# Wait for the snapshot to be available
						# thiw will fail after 60 failed checks
						aws rds wait db-snapshot-available \
							--db-instance-identifier "${rdsClusterIdentifier}" \
							--db-snapshot-identifier "${snapshotId}"
						
						if [ \$? -eq 0 ]; then
							echo "Snapshot ${snapshotId} upgraded to ${postgresqlTargetVersion} successfully!"							
							# Get snapshot details
							aws rds describe-db-snapshots \
								--db-snapshot-identifier "${snapshotId}" \
								--query 'DBSnapshots[0].{SnapshotID:DBSnapshotIdentifier,Status:Status,Created:SnapshotCreateTime}' \
								--output table 
                            echo "${BOLD}${GREEN}Snapshot ID: ${snapshotId}${END}"
						else
							echo "Error: Snapshot Upgrade may have failed. Please check AWS console."
						fi
					"""
				}
			}

			stage('Update RDS Snapshot') {
				dir('iac') {
					def iacDir = sh(returnStdout: true, script: "pwd").trim()
					def iacRdsNewDir = "${iacDir}/${tgDirectory}"
                    def iacRdsPath = "${iacDir}/${tgDirectory}/terragrunt.hcl"

                    def useSnapshot = sh(script: "grep -q 'snapshot_identifier' '${iacRdsPath}'", returnStatus: true) == 0
                    if (useSnapshot) {
                        sh "sed -i '/[[:space:]]*snapshot_identifier[[:space:]]*=/c\\ snapshot_identifier = \"${snapshotId}\"' '${iacRdsPath}'"
                    }
                    else {
                        sh "sed -i '/[[:space:]]*rds_instance_class[[:space:]]*=/a\\ snapshot_identifier = \"${snapshotId}\"' '${iacRdsPath}'"
                    }
			    }
            }

			stage('Apply RDS Upgrade') {
				dir('iac') {
					def iacDir = sh(returnStdout: true, script: "pwd").trim()
					def iacRdsNewDir = "${iacDir}/${tgDirectory}"

                    timeout(time: 60, unit: 'MINUTES') {
                        input(
                            id: 'userInput', 
                            message: 'Make sure to merge the last PR before proceeding!!!', 
                        )
                    }

                    dir("${iacRdsNewDir}"){
                        env.TGENV_AUTO_INSTALL="true"
                        sh "terragrunt hclfmt || true"
                        sh "terragrunt plan -out=plan.out"

                        timeout(time: 60, unit: 'MINUTES') {
                            input(
                                id: 'userInputTG', 
                                message: 'Please reveiw the plan before proceeding. Apply the changes?', 
                            )
                        }
                        sh "terragrunt apply -auto-approve plan.out"
                    }
			    }
            }

			stage('Commit and Push IaC Repo'){          
            	if (params.skipPRCreation) {
					echo "Skipping PR Creation"
				} else {
                    BB_BRANCH_NAME = "automated-pipeline-upgrade-rds16-${targetEnvironmentName}-${serviceGroup}"
                    def commitMsg = "feat: update to latest snapshot rds postgresql16 ${targetEnvironmentName} ${serviceGroup}"

                    dir('iac') {
                        sshagent(['bitbucket-repo-read-only']) {
                            sh """#!/bin/bash
                                set -e
                                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

                                git checkout -b ${BB_BRANCH_NAME}
                                git config --global user.email "build@accelbyte.net"
                                git config --global user.name "Build AccelByte"
                                git status --short
                                git add "${iacRdsNewPath}"
                                git commit -m "${commitMsg}"
                                git push --set-upstream origin ${BB_BRANCH_NAME}
                            """
                        }
                    }
                }
            
			}

			stage("Create PR IaC Repo") {

				if (params.skipPRCreation) {
					echo "Skipping PR Creation"
				} else {
					prSummary="""
					:: update to latest snapshot rds postgresql16 ${targetEnvironmentName} ${serviceGroup}\n \n
					"""
					withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
						// POST
						def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
						def postData =  [
							title: "feat: update to latest snapshot rds postgresql16 ${targetEnvironmentName} ${serviceGroup}",
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
							println(prHtmlLink);
						}
					}

				}
			}
        }
    }
}