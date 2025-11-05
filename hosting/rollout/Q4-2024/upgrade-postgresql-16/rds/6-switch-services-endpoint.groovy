import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "Target RDS Cluster to Upgrade to PostgreSQL 16", name: 'rdsClusterIdentifier', defaultValue: "" ),
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

String rdsPg12Endpoint
String rdsPg16Endpoint

def iacHasChanges = false

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

                rdsPg12Endpoint = sh(
					script: """
						aws rds describe-db-instances \
						--db-instance-identifier ${rdsClusterIdentifier} \
						--query 'DBInstances[0].Endpoint.[Address]' \
						--output text
					""",
					returnStdout: true
				).trim()

                rdsPg16Endpoint = sh(
					script: """
						aws rds describe-db-instances \
						--db-instance-identifier ${rdsClusterIdentifier}-pg16 \
						--query 'DBInstances[0].Endpoint.[Address]' \
						--output text
					""",
					returnStdout: true
				).trim()
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
                ///eks/loadtest/justice/prod/postgres/justice
                ///eks/loadtest/justice/prod/postgres_password
                ///eks/loadtest/justice/prod/analytics_postgres_password
                //Replace old internal domain r53 to a new internal r53
                //Replace old endpoint to a new endpoint
                //rds-justice.

                if(params.rdsClusterIdentifier.contains("analytic")) {
                    dir('deployments') {
                        sh """
                            find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/postgres/analytics#${ssmPath}/postgres/analytics_pg16#g' {} +
                            find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/analytics_postgres_#${ssmPath}/postgres/analytics_pg16_#g' {} +

                            find ${deploymentClusterDirectory} -type f -exec sed -i 's#rds-analytics.#rds-analytics-pg16.#g' {} +

                            find ${deploymentClusterDirectory} -type f -exec sed -i 's#${rdsPg12Endpoint}#${rdsPg16Endpoint}#g' {} +

                        """
                    }

                    dir('iac') {
                        sh """
                        find ${manifestClusterDirectory} -type f -exec sed -i 's#rds-analytics.#rds-analytics-pg16.#g' {} +
                        """
                    }			

                } else {
                        dir('deployments') {
                        sh """

                        find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/postgres/justice#${ssmPath}/postgres/justice_pg16#g' {} +
                        find ${deploymentClusterDirectory} -type f -exec sed -i 's#${ssmPath}/postgres_#${ssmPath}/postgres/justice_pg16_#g' {} +

                        find ${deploymentClusterDirectory} -type f -exec sed -i 's#rds-justice.#rds-justice-pg16.#g' {} +

                        find ${deploymentClusterDirectory} -type f -exec sed -i 's#${rdsPg12Endpoint}#${rdsPg16Endpoint}#g' {} +

                        """
                    }
                    dir('iac') {
                        sh """
                        find ${manifestClusterDirectory} -type f -exec sed -i 's#rds-justice.#rds-justice-pg16.#g' {} +
                        """
                    }				
                }
			}

			stage('Commit and Push Deployment Repo'){
                BB_BRANCH_NAME = "automated-pipeline-upgrade-rds16-${targetEnvironmentName}-switch-endpoint-${timeStamp}"
                def commitMsg = "feat: point services to rds postgresql16 ${targetEnvironmentName}"
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
							git commit -m "${commitMsg}"
							git push --set-upstream origin ${BB_BRANCH_NAME}
						"""
					}
				}
			}

			stage('Commit and Push IaC Repo'){
                BB_BRANCH_NAME = "automated-pipeline-upgrade-rds16-${targetEnvironmentName}-switch-endpoint-${timeStamp}"
                def commitMsg = "feat: point services/tools to rds postgresql16 ${targetEnvironmentName}"
				dir('iac') {
                    def changes = sh(script: 'git status --porcelain', returnStdout: true).trim()
                    if (changes) {
                        iacHasChanges = true
                        sshagent(['bitbucket-repo-read-only']) {
                            sh """#!/bin/bash
                                set -e
                                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                                git checkout -b ${BB_BRANCH_NAME}
                                git config --global user.email "build@accelbyte.net"
                                git config --global user.name "Build AccelByte"
                                git status --short
                                git add ${manifestClusterDirectory}
                                git commit -m "${commitMsg}"
                                git push --set-upstream origin ${BB_BRANCH_NAME}
                            """
                        }
                    } else {
                        echo "No changes in IaC!"
                    }
				}
			}

			stage("Create PR IaC Repo") {
                
                if(iacHasChanges) {
                    prSummary="""
                    :: Point services/tools to PostgreSQL 16 \n \n
                    """
                    withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
                        // POST
                        def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
                        def postData =  [
                            title: "feat: point services/tools to postgresql 16 ${targetEnvironmentName}",
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

                } else {
                    echo "Skipping PR creation for IaC since there's no changes!"
                }
			}

			stage("Create PR Deployment Repo") {
				prSummary="""
				:: Point services to PostgreSQL 16 \n \n
				"""
				withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
					// POST
					def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests").openConnection();
					def postData =  [
						title: "feat: point services to postgresql 16 ${targetEnvironmentName}",
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
                        message: 'Make sure to merge the last PR and wait for deployment to be finished if the env uses NewCD!!', 
                    )
                }

			}

			stage("Annotate ExternalSecrets") {
				sh"""
					kubectl annotate externalsecrets.external-secrets.io -A --all force-sync=\$(date +%s) --overwrite
					#Waiting for all secrets to be fetched
					sleep 30
				"""
			}
        }
    }
}