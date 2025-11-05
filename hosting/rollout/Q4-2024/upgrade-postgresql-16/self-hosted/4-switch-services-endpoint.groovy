import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
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

def createBBPullRequest(bbPRUrl, bbCreds, bbBranch, targetEnvironmentName) {
    // POST https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests
    def prSummary="""
	:: Point services to PostgreSQL 16 \n \n
	"""

    def post = new URL(bbPRUrl).openConnection();
    def postData =  [
        title: "feat: point services to postgresql 16 ${targetEnvironmentName}",
        source: [
            branch: [
                name: "${bbBranch}"
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
    post.setRequestProperty("Authorization", "Basic ${bbCreds}")
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

String targetEnvironmentName = params.targetEnvironmentName
String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-postgresql-upgrade"

node('hosting-agent') {
	container('tool') {
		dir(tempDir){

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

                    deploymentClusterDirectory = sh(returnStdout: true, script: """
						echo "${customer}/${project}/${environment}"
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

			stage('Clone deployments repository') {
				dir('deployments') {
                checkout scmGit(
                    branches: [[name: '*/master']],
                    extensions: [ cloneOption(shallow: true) ],
                    userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                        url: 'git@bitbucket.org:accelbyte/deployments.git']])
				}
			}

			stage('Point Services Config to PostgreSQL 16') {
				dir('deployments') {

                    //    - secretKey: DATABASE_HOST
                    //   remoteRef:
                    //     key: /eks/foundations/justice/internal/postgres/postgresql12_address
					sh """
					#postgresql12_address
					#postgresql12_port_int
					#postgresql12_port_str
					#postgresql12_username
					#postgresql12_password
					find ${deploymentClusterDirectory} -type f -exec sed -i 's/postgresql12/postgresql16/g' {} +

					#analytics_postgres_password
					#analytics_postgres_username
					find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/analytics_postgres_username#${ssmPath}/postgres/postgresql16_username#g' {} +
					find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/analytics_postgres_password#${ssmPath}/postgres/postgresql16_password#g' {} +
					find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/postgres/postgresql12_#${ssmPath}/postgres/postgresql16_#g' {} +

					#/eks/abcdexample/justice/dev2/postgres_password
					#/eks/abcdexample/justice/dev2/postgres_username
					find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/postgres_username#${ssmPath}/postgres/postgresql16_username#g' {} +
					find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/postgres_password#${ssmPath}/postgres/postgresql16_password#g' {} +
					"""
				}

				dir('iac') {
					//${SSM_PATH}/postgres_username
					//${SSM_PATH}/postgres_password
					sh """

                    find ${manifestClusterDirectory} -type f -exec sed -i 's#postgres_username#postgres/postgresql16_username#g' {} +
					find ${manifestClusterDirectory} -type f -exec sed -i 's#postgres_password#postgres/postgresql16_password#g' {} +
					
                    # postgresql12.postgresql:5432/postgres?sslmode=disable
					find ${manifestClusterDirectory} -type f -exec sed -i 's#postgresql12\\.postgresql#postgresql16.postgresql#g' {} +
                    """
                    //Let the postgresql SSM job handles this
                    //postgresql exporter? -> target specific file only
                    //turns out this is handled by secret-initializer too so userpass should be the same with current postgres
                    //${SSM_PATH}/monitoring/postgres_exporter/data_source_user
                    //${SSM_PATH}/monitoring/postgres_exporter/data_source_pass

				}

				//Old configmap not managed with GitOps
				//Replace all postgresql12 occurences just to be safe
				sh '''
                kubectl get configmap justice-variables -n justice -o yaml | \
                sed 's/postgresql12/postgresql16/g' | \
                kubectl apply -f - || true

                kubectl get configmap justice-common-variables -n justice -o yaml | \
                sed 's/postgresql12/postgresql16/g' | \
                kubectl apply -f - || true

				kubectl get configmap analytics-airflow-config -n justice -o yaml | \
                sed 's/postgresql12/postgresql16/g' | \
                kubectl apply -f - || true    	
				'''
				
			}

			stage('Commit and Push Deployment & IaC Repo'){
				BB_BRANCH_NAME = "jenkins-${targetEnvironmentName}-switch-services-ep-psql16-${timeStamp}"
				dir('deployments') {
					sshagent(['bitbucket-repo-read-only']) {
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							git checkout -b ${BB_BRANCH_NAME}
							git config --global user.email "build@accelbyte.net"
							git config --global user.name "Build AccelByte"
							git status --short
							git add ${deploymentClusterDirectory}
							git commit -m "feat: ${BB_BRANCH_NAME}"
							git push --set-upstream origin ${BB_BRANCH_NAME}
						"""
					}
				}

				dir('iac') {
					sshagent(['bitbucket-repo-read-only']) {
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							git checkout -b ${BB_BRANCH_NAME}
							git config --global user.email "build@accelbyte.net"
							git config --global user.name "Build AccelByte"
							git status --short
							git add ${manifestClusterDirectory}
							git commit -m "feat: ${BB_BRANCH_NAME}"
							git push --set-upstream origin ${BB_BRANCH_NAME}
						"""
					}
				}

			}

			stage("Create PR Deployment Repo") {

				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
                    deploymentRepoPRURL = "https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests"
                    iacRepoPRURL = "https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests"

                    createBBPullRequest(deploymentRepoPRURL, BuildAccountBitbucketAuthBasicB64, BB_BRANCH_NAME, targetEnvironmentName)
                    createBBPullRequest(iacRepoPRURL, BuildAccountBitbucketAuthBasicB64, BB_BRANCH_NAME, targetEnvironmentName) 

				}
			}

			stage("Annotate ExternalSecrets") {

				timeout(time: 90, unit: 'MINUTES') {
					input(
						id: 'userInput', 
						message: 'Make sure to merge the PRs and wait for all deployment to be finished before proceeding!!!', 
					)
				}
                
				sh"""
					kubectl annotate externalsecrets.external-secrets.io -A --all force-sync=\$(date +%s) --overwrite
					#Waiting for all secrets to be fetched
					sleep 30
				"""
			}

			stage('Reconcile IaC'){

                sh """
                    curl -LO https://github.com/fluxcd/flux2/releases/download/v0.38.3/flux_0.38.3_linux_amd64.tar.gz
                    tar -xvzf flux_0.38.3_linux_amd64.tar.gz
                    chmod +x flux
                    mv flux /usr/local/bin
                """
        
                sh """
                    flux reconcile kustomization -n flux-system flux-system --with-source || true
					flux reconcile kustomization -n flux-system cluster-roles --with-source || true
                    flux reconcile kustomization -n flux-system cluster-variables --with-source || true
					flux reconcile kustomization -n flux-system external-secrets-operator || true
					flux reconcile kustomization -n flux-system cluster-secret-store || true
                    flux reconcile kustomization -n flux-system cluster-secrets || true
                    flux reconcile kustomization -n flux-system storageclass || true
                    flux reconcile kustomization -n flux-system flux-volume || true
					flux reconcile kustomization -n flux-system karpenter || true
					flux reconcile kustomization -n flux-system karpenter-templates || true
                    flux reconcile kustomization -n flux-system flux || true
                    flux reconcile kustomization -n flux-system monitoring || true
                    flux reconcile kustomization -n flux-system prom-stack || true
                    flux reconcile kustomization -n flux-system postgresql16 || true
                """

			}

			stage("Rollout Restart Services") {

                timeout(time: 60, unit: 'MINUTES') {
					input(
						id: 'userInput', 
						message: 'Are you sure to restart all deployment in namespace: justice?')
				}

				//To-Do: Better find a way to make sure which namespaces affected by the migration
				//and restart all workload in that namespaces
				sh"""
					kubectl rollout restart deploy -n justice

				"""
			}
        }
    }
}