import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
			string(description: "TG Directory IaC of the Target RDS e.g live/455912570532/sandbox/justice/us-east-2/dev/rds/justice-shared-pg16", name: 'tgDirectory', defaultValue: "" ),
			text(description: "AWS Credentials. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" )
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
String tgDirectory = params.tgDirectory.endsWith('/') ? params.tgDirectory[0..-2] : params.tgDirectory
String serviceGroup = tgDirectory.split('/')[-1]
String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

String validationScriptPath= "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/scripts/validation-rds.sh"

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
			}

			stage("Validate Database") {

				def pathPrefixPG12
				def pathPrefixPG16
				if (serviceGroup.contains("justice-shared")) {
					pathPrefixPG12 = "postgres/justice"
					pathPrefixPG16 = "postgres/justice"
				} else if (serviceGroup.contains("analytic")) {
					pathPrefixPG12 = "analytics_postgres"
					pathPrefixPG16 = "postgres/analytics"
				} else {
					pathPrefixPG12 = "postgres/session_history"
					pathPrefixPG16 = "postgres/session_history"
				}

				postgresql16Password = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/${pathPrefixPG16}_pg16_password\" \
						--with-decryption | jq .Parameter.Value
				""").trim()
				postgresql16Username = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/${pathPrefixPG16}_pg16_username\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				postgresql16Host = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/${pathPrefixPG16}_pg16_address\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				postgresql12Password = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/${pathPrefixPG12}_password\" \
						--with-decryption | jq .Parameter.Value
				""").trim()
				postgresql12Username = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/${pathPrefixPG12}_username\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				postgresql12Host = sh(returnStdout: true, script: """
					aws ssm get-parameter\
						--region ${awsRegion} \
						--name \"${ssmPath}/${pathPrefixPG12}_address\" \
						--with-decryption | jq .Parameter.Value
				""").trim()

				sh """
				set +x
				kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
				echo "export PGPASSWORD=${postgresql12Password}" > pgpass_${serviceGroup}
				echo "export PGUSER=${postgresql12Username}" >> pgpass_${serviceGroup}
				echo "export PGUSER_NEW=${postgresql16Username}" >> pgpass_${serviceGroup}
				echo "export PGPASSWORD_NEW=${postgresql16Password}" >> pgpass_${serviceGroup}

				echo "export OLD_HOST=${postgresql12Host}" >> pgpass_${serviceGroup}
				echo "export NEW_HOST=${postgresql16Host}" >> pgpass_${serviceGroup}
				'
				"""

				sh """
					kubectl cp ${validationScriptPath} tools/jumpbox-postgresql-migration-0:/migration/validation.sh 
					kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
					source pgpass_${serviceGroup}
					chmod +x validation.sh
					bash validation.sh 2>&1 | tee output.txt
					cat output.txt | grep MISMATCH || true
					'
				"""
			}	

        }
    }
}