import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "Target RDS Cluster to Upgrade to PostgreSQL 16", name: 'rdsClusterIdentifier', defaultValue: "" ),
            string(description: "Target PostgreSQL Version. Please refer to https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_UpgradeDBInstance.PostgreSQL.MajorVersion.html", name: 'postgresqlTargetVersion', defaultValue: "16.1" ),
            string(description: "Service Group.", name: 'serviceGroup', defaultValue: "justice" ),
			text(description: "AWS Credentials for running terragrunt apply. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),
			booleanParam(name: 'skipSnapshotUpgrade', defaultValue: false, description: 'Skip Snapshot Upgrade')
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
String serviceGroup = params.serviceGroup.trim()

String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

String validationScriptPath= "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/scripts/validation.sh"
String jumpboxYaml = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/manifests/jumpbox.yaml"

String snapshotId = "${targetEnvironmentName}-before-pg16-upgrade-${serviceGroup}"

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-${serviceGroup}-rds-postgresql-upgrade"

String GREEN='\033[01;32m'
String BOLD='\033[1m'
String END='\033[00m'

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


					manifestClusterDirectory = sh(returnStdout: true, script: """
						find manifests -path "*/${customer}/${project}/${awsRegion}/${environment}"
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

			stage('Generate Kubeconfig') {
				sh """#!/bin/bash
					set -e
					set -o pipefail
					envsubst < ~/.aws/config.template > ~/.aws/config
					aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
				"""
			}

			stage('Prepare common variable') {
				ssmPath = sh(returnStdout: true, script: """
					kubectl -n justice get cm cluster-variables -oyaml | yq .data.SSM_PATH
				""").trim()		
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
        }
    }
}
