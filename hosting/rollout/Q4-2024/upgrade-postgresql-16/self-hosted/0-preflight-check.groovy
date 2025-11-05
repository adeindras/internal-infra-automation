import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            text(description: "AWS Credentials. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),

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

String jumpboxYaml = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/manifests/jumpbox.yaml"
String checkDBScriptPath = "internal-infra-automation/hosting/rollout/Q4-2024/upgrade-postgresql-16/scripts/check-db.sh"

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

			stage('Deploy sts jumpbox') {
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


					kubectl apply -f ${jumpboxYaml}
				"""
			}

			stage('Preflight Check') {

                sh """
                set +x
                kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash -c '
                echo "export PGPASSWORD=${postgresql12Password}" >> pgpass
                echo "export PGUSER=${postgresql12Username}" >> pgpass
                echo "export PGHOST=postgresql16.postgresql" >> pgpass
                echo "export TARGET_ENV=${targetEnvironmentName}" >> pgpass
                '
                """

                sh """
                kubectl cp ${checkDBScriptPath} tools/jumpbox-postgresql-migration-0:/migration/checkdb.sh 
                kubectl exec jumpbox-postgresql-migration-0 -n tools -- chmod +x checkdb.sh
                kubectl exec jumpbox-postgresql-migration-0 -n tools -- bash checkdb.sh
				"""


			}

			stage('Test Flux Reconciliation'){

                sh """
                    curl -LO https://github.com/fluxcd/flux2/releases/download/v0.38.3/flux_0.38.3_linux_amd64.tar.gz
                    tar -xvzf flux_0.38.3_linux_amd64.tar.gz
                    chmod +x flux
                    mv flux /usr/local/bin
                """
        
                sh """
					#Suspend-Resume can trigger all kustomization reconciliation 
					#ref: https://github.com/fluxcd/flux2/discussions/4794#discussioncomment-10541698
					#Can potetntially cause issue if some kustomization is intended to be suspended
					#flux suspend kustomization --all || true
					#flux resume kustomization --all || true
					
                    flux reconcile kustomization -n flux-system flux-system --with-source
					flux reconcile kustomization -n flux-system cluster-roles --with-source
                    flux reconcile kustomization -n flux-system cluster-variables --with-source
                """

			}

        }
    }
}