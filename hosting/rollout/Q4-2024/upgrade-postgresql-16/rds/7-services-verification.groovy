import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "String to search in k8s", name: 'searchStrings', defaultValue: "postgresql12.postgresql" ),			
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

String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

FOUND_SERVICES = []
def podsContainers = []

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

					manifestClusterDirectory = sh(returnStdout: true, script: """
						find manifests -path "*/${customer}/${project}/${awsRegion}/${environment}"
					"""
					).trim()

                    deploymentClusterDirectory = sh(returnStdout: true, script: """
						echo "${customer}/${project}/${environment}"
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
         
			stage("Verifying Services") {
				externalSecretYaml = sh(
						returnStdout: true,
						script: "kubectl -n justice get externalsecrets.external-secrets.io -oyaml"
				).trim()
				writeFile(file: "external-secret-${targetEnvironmentName}.yaml", text: externalSecretYaml)
				listsDocDBServices = sh(
					returnStdout: true,
					script: """
						set +x
						yq '.items[] | select(.spec.data[]?.remoteRef.key == "*postgre*") | .metadata.name' external-secret-${targetEnvironmentName}.yaml | grep -v flux | grep -v job | awk -F'-secret' '{print \$1}'
					"""
				).trim().split('\n')

				sh "touch pods-running-${targetEnvironmentName}-${timeStamp}.yaml"

				for (service in listsDocDBServices) {
					sh """
						kubectl -n justice get pod -l 'app=${service}' -o 'custom-columns=:metadata.name' | grep -v job >> pods-running-${targetEnvironmentName}-${timeStamp}.yaml || true
					"""
				}
				sh """
					sed -i '/^\$/d' pods-running-${targetEnvironmentName}-${timeStamp}.yaml
					cat pods-running-${targetEnvironmentName}-${timeStamp}.yaml
				"""

				def pods = readFile("pods-running-${targetEnvironmentName}-${timeStamp}.yaml").trim().split('\n')
				
				for (pod in pods) {
					containers = sh(
						returnStdout: true,
						script: """
							set +x
							kubectl -n justice get pod "${pod}" -o yaml | yq eval '.spec.containers[].name' - | grep -Ev 'max|opentelemetry|init|linkerd' | awk -v pod="${pod}" '{print pod"---"\$0}'
						"""
					).trim().split('\n')

					podsContainers.addAll(containers)
				}

				for (podContainer in podsContainers) {
					if (podContainer.trim()) {
						def (pod, container) = podContainer.split('---')
						
						if (pod && container) {
								sh """
										set +x
										echo "-----------------------------------------------------"
										if \$(kubectl -n justice exec -t ${pod.trim()} -c ${container.trim()} -- printenv | grep -q "${searchStrings}"); then {
											>&2 echo "Error: found pattern ${searchStrings} on this pod ${pod}"        
										} else {
											echo "Not found pattern ${searchStrings} on this pod ${pod}"
										}
										fi
								"""
						} else {
								echo "Warning: Unable to parse pod and container from '${podContainer}'"
						}
					} else {
						echo "Warning: podContainer is empty, skipping..."
					}
				}
			}

        }
    }
}
