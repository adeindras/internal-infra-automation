import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "Target RDS Cluster to run post-migration activity against", name: 'rdsClusterIdentifier', defaultValue: "" ),
            string(description: "Target RDS Cluster Username", name: 'rdsUsername', defaultValue: "" ),
            password(description: "Target RDS Cluster Password", name: 'rdsPassword', defaultValue: "" ),
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

def isPodRunning(podName, namespace) {
    def result = sh(script: "kubectl get pod ${podName} -n ${namespace} -o jsonpath='{.status.phase}'", returnStdout: true).trim()
    return result == 'Running'
}

String targetEnvironmentName = params.targetEnvironmentName
String rdsClusterIdentifier = params.rdsClusterIdentifier

String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

String analyzeScript = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/scripts/analyze.sh"
String jumpboxYaml = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/manifests/jumpbox.yaml"

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-rds-postgresql-upgrade"

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

			stage('Generate Kubeconfig') {
				sh """#!/bin/bash
					set -e
					set -o pipefail
					envsubst < ~/.aws/config.template > ~/.aws/config
					aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
				"""
			}

			stage('Fetch required manifests and scripts') {
				dir('internal-infra-automation') {
                checkout scmGit(
                    branches: [[name: '*/no-jira-upgrade-psql16']],
                    extensions: [ cloneOption(shallow: true) ],
                    userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                        url: 'git@bitbucket.org:accelbyte/internal-infra-automation.git']])
				}
			}

			stage('Deploy sts jumpbox') {
                def podName = 'jumpbox-postgresql-migration-0'
                def namespace = 'tools'
                def timeout = 300 // Timeout in seconds

				postgresql16Username = params.rdsUsername
				postgresql16Password = params.rdsPassword
				def postgresql16Host = sh(
					script: """
						aws rds describe-db-instances \
						--db-instance-identifier ${rdsClusterIdentifier} \
						--query 'DBInstances[0].Endpoint.[Address]' \
						--output text
					""",
					returnStdout: true
				).trim()

                sh """
					set -eo pipefail
					if kubectl get namespace tools >/dev/null 2>&1; then
						echo "Namespace tools already exists."
					else
						echo "Namespace tools does not exist. Creating it..."
						kubectl create namespace tools
						if [ \$? -eq 0 ]; then
							echo "Namespace tools created successfully."
						else
							echo "Failed to create namespace tools."
						fi
					fi

					kubectl apply -f ${jumpboxYaml} || true
				"""

                def startTime = System.currentTimeMillis()

                while (!isPodRunning(podName, namespace)) {
                    def elapsedTime = (System.currentTimeMillis() - startTime) / 1000 // Elapsed time in seconds
                    
                    if (elapsedTime >= timeout) {
                        error "Giving up: Pod ${podName} did not start running after ${timeout} seconds."
                    }
                    
                    echo "Pod ${podName} is not running. Waiting for 5 seconds..."
                    sleep(5)
                }

                sh "kubectl cp ${analyzeScript} ${namespace}/${podName}:/migration/analyze.sh"

				sh """
				set +x
				kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
				echo "export PGPASSWORD=${postgresql16Password}" > pgpass
				echo "export PGUSER=${postgresql16Username}" >> pgpass
                echo "export PGHOST=${postgresql16Host}" >> pgpass
				'
				"""
			}

			stage("Analyze All Databases") {
				sh """
                    kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash /migration/analyze.sh
				"""
			}
        }
    }
}
