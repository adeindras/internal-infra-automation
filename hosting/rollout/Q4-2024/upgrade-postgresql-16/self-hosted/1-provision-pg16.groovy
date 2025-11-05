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

			stage('Provision PostgreSQL 16 IaC') {
				newPGPath="${manifestClusterDirectory}/sync/extended/postgresql16.yaml"
				oldPGPath="${manifestClusterDirectory}/sync/extended/postgresql.yaml"
				extendedKustomizationPath="${manifestClusterDirectory}/sync/extended/kustomization.yaml"
				jumpboxUbuntuPath="${manifestClusterDirectory}/sync/extended/jumpbox-ubuntu.yaml"
				jumpboxAlpinePath="${manifestClusterDirectory}/sync/extended/jumpbox.yaml"

				dir("iac") {
					echo "Copying postgresql 16"
					sh """
						cp ${oldPGPath} ${newPGPath}

						yq '.metadata.name = "postgresql16"' -i ${newPGPath}
						yq '.spec.path = "./manifests/platform/postgresql/v0.2.5-2/karpenter-cw"' -i ${newPGPath}
						yq '.spec.postBuild.substitute.POSTGRESQL_NAME  = "postgresql16"' -i ${newPGPath}
						yq '.spec.postBuild.substitute.POSTGRESQL_VERSION  = "16.4-alpine3.20"' -i ${newPGPath}
						yq '.spec.postBuild.substitute.POSTGRESQL_PVC_SIZE  = "20Gi"' -i ${newPGPath}
						#POSTGRESQL_PVC_SIZE
						#unset POSTGRESQL_PVC_NAME

						yq '.bases += ["./postgresql16.yaml"]' -i ${extendedKustomizationPath}

					"""

					echo "Updating jumpbox image..."
					sh """
						yq '.spec.path = "./manifests/platform/jumpbox/ubuntu/v20.04-3"' -i ${jumpboxUbuntuPath} || true
						yq '.spec.path = "./manifests/platform/jumpbox/alpine/v1.1.2"' -i ${jumpboxAlpinePath} || true
					"""
				}
			}

			stage('Commit and Push IaC Repo'){
				BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-deploy-psql16-${timeStamp}"
				dir('iac') {
					sshagent(['bitbucket-repo-read-only']) {
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

							git checkout -b ${BB_BRANCH_NAME}
							git config --global user.email "build@accelbyte.net"
							git config --global user.name "Build AccelByte"
							git status --short 
							chmod -R 644 ${manifestClusterDirectory} || true
							git add ${manifestClusterDirectory}
							git commit -m "feat: provision postgresql16 ${targetEnvironmentName}"
							git push --set-upstream origin ${BB_BRANCH_NAME}
						"""
					}
				}
			}

			stage("Create PR IaC Repo") {
				prSummary="""
				:: Provision PostgreSQL 16\n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
					def postData =  [
						title: "feat: provision postgresql 16 ${targetEnvironmentName}",
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

				timeout(time: 60, unit: 'MINUTES') {
					input(
						id: 'userInput', 
						message: 'Make sure to merge the last PR before proceeding!!!', 

					)
				}
			}

			stage('Reconcile IaC'){

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
					
                    flux reconcile kustomization -n flux-system flux-system --with-source || true
					flux reconcile kustomization -n flux-system cluster-roles --with-source || true
                    flux reconcile kustomization -n flux-system cluster-variables --with-source || true
					flux reconcile kustomization -n flux-system external-secrets-operator || true
					flux reconcile kustomization -n flux-system cluster-secret-store || true
                    flux reconcile kustomization -n flux-system cluster-secrets || true
                    flux reconcile kustomization -n flux-system storageclass || true
                    flux reconcile kustomization -n flux-system flux-volume || true
					flux reconcile kustomization -n flux-system karpenter || true
					#take a breather
					sleep 5
					flux reconcile kustomization -n flux-system karpenter-templates || true
                    flux reconcile kustomization -n flux-system flux || true
                    flux reconcile kustomization -n flux-system monitoring || true
                    flux reconcile kustomization -n flux-system prom-stack || true
                    flux reconcile kustomization -n flux-system postgresql16 || true
                """

			}

        }
    }
}