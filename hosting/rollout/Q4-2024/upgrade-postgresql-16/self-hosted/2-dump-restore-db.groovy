import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
			text(description: "AWS Credentials. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),
			string(name: 'excludeDatabases', defaultValue: '', description: 'Comma-separated list of databases to exclude from pg_dumpall (e.g. db1, db2, db3)'),
			booleanParam(name: 'cleanMigration', defaultValue: '', description: 'BEWARE!! This options ignore excludeDatabases!! Emit SQL commands to DROP all the dumped databases, roles, and tablespaces before recreating them. This option is useful when the restore is to overwrite an existing cluster'),
            booleanParam(name: 'scaledownServices', defaultValue: false, description: 'Mandatory during migration. Scaledown services during migration to prevent write to db.'),
			booleanParam(name: 'skipDBDump', defaultValue: false, description: 'Skip dumping databases from Postgresql 12.'),
			booleanParam(name: 'skipDBRestore', defaultValue: false, description: 'Skip restoring databases to Postgresql 16.')

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
String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

String networkPolicyYaml = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/manifests/networkpolicy.yaml"
String dropRoleScriptPath = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/scripts/drop-role-dump.sh"
String reindexScriptPath = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/scripts/reindex.sh"

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-postgresql-upgrade"

node('hosting-agent') {
	container('tool') {
		dir(tempDir){

			stage('Fetch required manifests and scripts') {
				dir('internal-infra-automation') {
                    checkout scmGit(
                        branches: [[name: '*/no-jira-upgrade-psql16']],
                        extensions: [ cloneOption(shallow: true) ],
                        userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                            url: 'git@bitbucket.org:accelbyte/internal-infra-automation.git']])
				}
			}

			stage('Clone iac repository') {
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

				postgresql12Address = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql12_address\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				postgresql12Password = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql12_password\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				postgresql12Username = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql12_username\" \
						--with-decryption | jq .Parameter.Value
				""").trim()		
			}

			stage("Scaledown Services") {

				if(params.scaledownServices) {
					timeout(time: 30, unit: 'MINUTES') {
						input(
							id: 'userInput', 
							message: 'Services using postgresql will be scaled down. This will cause OUTAGE/DOWNTIME. U sure u wanna proceed?', 
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
							kubectl -n justice get po | grep ${service} || true
							kubectl -n justice scale deploy ${service} --replicas=0 || true
						"""
					}

					sh "kubectl -n justice scale deploy analytics-airflow-scheduler --replicas=0 || true"

					//Waiting for graceful termination
					sleep(60)
				} else {
					echo "Skipping services scaledown. NO OUTAGE/DOWNTIME."
				}
			}


			stage("Dump PostgreSQL 12 Databases") {
				if(params.skipDBDump) {
					echo "Skip dumping databases from postgresql 12..."
				} else {

                    def pgDumpallCommand = "pg_dumpall -h postgresql12.postgresql -f dumpall-${targetEnvironmentName}.sql -w"

                    def excludeDatabases = params.excludeDatabases.trim()
                    // If there are databases to exclude, add the exclude flags to the command
                    if (excludeDatabases) {
                        def excludeFlags = excludeDatabases.split(',').collect { "--exclude-database=" + it.trim() }.join(' ')
                        pgDumpallCommand += " " + excludeFlags
                    }

					if(params.cleanMigration){
						pgDumpallCommand += " " + "--clean --if-exists" 
					}

					sh """
					set +x
					kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
					echo "export PGPASSWORD=${postgresql12Password}" >> pgpass
					echo "export PGUSER=${postgresql12Username}" >> pgpass
					echo "export TARGET_ENV=${targetEnvironmentName}" >> pgpass
					'
					"""

					sh """
					#To-Do:
					#Check jumpbox is running

					#Prepare pg_dumpall of PostgreSQL 16
					kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
						apt update && apt install -y lsb-release
						echo "deb http://apt.postgresql.org/pub/repos/apt \$(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list
						curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --yes --dearmor -o /etc/apt/trusted.gpg.d/postgresql.gpg
						apt update && apt install -y postgresql-16 postgresql-contrib-16
					'
					"""

					def psqlVersion = sh(returnStdout: true, script: """
						kubectl exec -n tools jumpbox-postgresql-migration-0 -- psql --version 2>&1
					""").trim()
					// Extract the major version number (e.g., "psql (PostgreSQL) 16.1")
					def majorVersion = psqlVersion.tokenize()[2].split("\\.")[0]
					if (majorVersion != '16') {
						currentBuild.result = 'ABORTED'
						error('psql version is not 16. Consider re-running the pipeline. Exiting...')
					}

					sh """

						echo "Dumping old databases. Might take a while...."
						kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
						source pgpass
						${pgDumpallCommand}
						'
						kubectl exec jumpbox-postgresql-migration-0 -n tools -- ls -lah dumpall-${targetEnvironmentName}.sql
						
					"""

					//Ignore Roles if possible since username usually the same between pg12 and pg16 and changing password will break the script.
					sh """
						kubectl cp ${dropRoleScriptPath} tools/jumpbox-postgresql-migration-0:/migration/drop-role.sh 
						kubectl exec jumpbox-postgresql-migration-0 -n tools -- chmod +x drop-role.sh
						kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash drop-role.sh

					"""
				}
			}

			stage("Restore Databases into PostgreSQL 16") {
				if(params.skipDBRestore) {
					echo "Skip restoring databases to postgresql 16..."
				} else {
					postgresql16Password = sh(returnStdout: true, script: """
						aws ssm get-parameter\
							--region ${awsRegion} \
							--name \"${ssmPath}/postgres/postgresql16_password\" \
							--with-decryption | jq .Parameter.Value
					""").trim()
					postgresql16Username = sh(returnStdout: true, script: """
						aws ssm get-parameter\
							--region ${awsRegion} \
							--name \"${ssmPath}/postgres/postgresql16_username\" \
							--with-decryption | jq .Parameter.Value
					""").trim()

					sh """
					set +x
					kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
					echo "export PGPASSWORD=${postgresql16Password}" > pgpass
					echo "export PGUSER=${postgresql16Username}" >> pgpass
					'
					"""

					sh """
						kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
						source pgpass
						psql -h postgresql16.postgresql -d postgres -f dumpall-${targetEnvironmentName}.sql
						'
					"""
				}
			}

			stage("Reindex All Databases") {
				postgresql16Password = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql16_password\" \
						--with-decryption | jq .Parameter.Value
				""").trim()
				postgresql16Username = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql16_username\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				postgresql16Address = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql16_address\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				sh """
				set +x
				kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
				echo "export PGPASSWORD=${postgresql16Password}" > pgpass
				echo "export PGUSER=${postgresql16Username}" >> pgpass
				echo "export PGHOST=${postgresql16Address}" >> pgpass
				'
				"""

				sh """
				kubectl cp ${reindexScriptPath} tools/jumpbox-postgresql-migration-0:/migration/reindex.sh 
				kubectl exec jumpbox-postgresql-migration-0 -n tools -- chmod +x reindex.sh
				kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
				source pgpass
				bash reindex.sh
				'
				"""
			}

			stage("Analyze All Databases") {
				postgresql16Password = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql16_password\" \
						--with-decryption | jq .Parameter.Value
				""").trim()
				postgresql16Username = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/postgres/postgresql16_username\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				sh """
				set +x
				kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
				echo "export PGPASSWORD=${postgresql16Password}" > pgpass
				echo "export PGUSER=${postgresql16Username}" >> pgpass
				'
				"""

				sh """
					kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
					source pgpass
					vacuumdb -a -z -h postgresql16.postgresql
					'
				"""
			}
        }
    }
}