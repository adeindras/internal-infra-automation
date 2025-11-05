import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "TG Directory IaC of the Target RDS e.g live/455912570532/sandbox/justice/us-east-2/dev/rds/justice-shared", name: 'tgDirectory', defaultValue: "" ),
            string(description: "Target PostgreSQL Version. Please refer to https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_UpgradeDBInstance.PostgreSQL.MajorVersion.html", name: 'postgresqlTargetVersion', defaultValue: "16.1" ),
            string(description: "RDS Snapshot name. Snapshot will be used to spawn new RDS", name: 'snapshotId', defaultValue: "" ),
			text(description: "AWS Credentials for running terragrunt apply. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" ),
			booleanParam(name: 'skipPRCreation', defaultValue: false, description: 'Skip Pull Request Creation in Bitbucket'),
			booleanParam(name: 'skipRDSProvisioning', defaultValue: false, description: 'Skip RDS Provisioning and go straight to running TG Plan'),

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
String postgresqlTargetVersion = params.postgresqlTargetVersion
String snapshotId = params.snapshotId
String serviceGroup = tgDirectory.split('/')[-1]


String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

String iacDir
String iacRdsDir
String iacRdsPath
String iacRdsNewDir
String iacRdsNewPath


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

			stage('Provision New RDS with IaC') {
				dir("iac") {
					//arn-example: arn:aws:rds:us-east-2:057247111273:db:rds-telltale-justice-prod-analytics
					//tgDirectory example: live/455912570532/sandbox/justice/us-east-2/dev/rds/justice-shared
					//terragrunt blueprint: iac/live/948940033937/abcdexample/justice/us-east-2/prod/rds/justice-shared
					iacDir = sh(returnStdout: true, script: "pwd").trim()
					iacRdsDir = "${iacDir}/${tgDirectory}"
					iacRdsPath = "${iacDir}/${tgDirectory}/terragrunt.hcl"
					iacRdsNewDir = "${iacDir}/${tgDirectory}-pg16"
					iacRdsNewPath = "${iacDir}/${tgDirectory}-pg16/terragrunt.hcl"
					iacRdsTemplatePath = "${iacDir}/live/948940033937/abcdexample/justice/us-east-2/prod/rds/justice-shared/terragrunt.hcl"

					if(params.skipRDSProvisioning) {
						echo "Skipping RDS provisioning..."

					} else {
						echo "Copying rds TG manifest..."
						if (!fileExists(iacRdsDir)) {
							currentBuild.result = 'ABORTED'
							error('RDS directory does not exist. Exiting...')
						}

						sh "mkdir -p '${iacRdsNewDir}'"							
						sh "cp '${iacRdsPath}' '${iacRdsNewPath}'"

						def iacServiceGroup = sh(returnStdout: true , script: """sed -n 's/.*service[[:space:]]*=[[:space:]]*"\\([^"]*\\)".*/\\1/p' '${iacRdsPath}'""").trim()
						def rdsStorage = sh(returnStdout: true , script: """sed -n 's/.*rds_storage[[:space:]]*=[[:space:]]*"\\([^"]*\\)".*/\\1/p' '${iacRdsPath}'""").trim()
						def maxAllocatedStorage = sh(returnStdout: true , script: """sed -n 's/.*max_allocated_storage[[:space:]]*=[[:space:]]*"\\([^"]*\\)".*/\\1/p' '${iacRdsPath}'""").trim()

						def newServiceGroup = "${iacServiceGroup}-pg16"
						def sourceModuleVersion = "../../../../../../../../modules//rds/v5.6.0"

						def useSnapshot = sh(script: "grep -q 'snapshot_identifier' '${iacRdsNewPath}'", returnStatus: true) == 0
						if (useSnapshot) {
							sh "sed -i '/[[:space:]]*snapshot_identifier[[:space:]]*=/c\\ snapshot_identifier = \"${snapshotId}\"' '${iacRdsNewPath}'"
						}
						else {
							sh "sed -i '/[[:space:]]*rds_instance_class[[:space:]]*=/a\\ snapshot_identifier = \"${snapshotId}\"' '${iacRdsNewPath}'"
						}

						def familyExists = sh(script: "grep -q 'family' '${iacRdsNewPath}'", returnStatus: true) == 0
						if (familyExists) {
							sh "sed -i '/[[:space:]]*family[[:space:]]*=/c\\ family = \"postgres16\"' '${iacRdsNewPath}'"
						}
						else {
							sh "sed -i '/[[:space:]]*rds_instance_class[[:space:]]*=/a\\ family = \"postgres16\"' '${iacRdsNewPath}'"
						}

						def customerNameExists = sh(script: "grep -P 'customer_name\\s+=\\s+local.customer_name' '${iacRdsNewPath}'", returnStatus: true) == 0
						if (customerNameExists) {
							sh "sed -i '/ssm_rds_username_path[[:space:]]*= /s/^/#/' '${iacRdsNewPath}'"
							sh "sed -i '/ssm_rds_password_path[[:space:]]*= /s/^/#/' '${iacRdsNewPath}'"
							sh "sed -i '/ssm_rds_address_path[[:space:]]*= /s/^/#/' '${iacRdsNewPath}'"
							sh "sed -i '/ssm_rds_port_int_path[[:space:]]*= /s/^/#/' '${iacRdsNewPath}'"
							sh "sed -i '/ssm_rds_port_str_path[[:space:]]*= /s/^/#/' '${iacRdsNewPath}'"

						}

						def parametersExists = sh(script: "grep -P 'parametes\\s+=' '${iacRdsNewPath}'", returnStatus: true) == 0
						if(parametersExists){
							def parametersConcatExists = sh(script: "grep -P 'parameters\\s+=\\s+concat\\(\\[' '${iacRdsNewPath}'", returnStatus: true) == 0
							if (parametersConcatExists) {
							  sh "sed -i '/[[:space:]]*parameters[[:space:]]*=/c\\ parameters = concat([\n    {\n      apply_method = \"pending-reboot\"\n      name = \"rds.force_ssl\"\n      value = \"0\"\n    },\n' '${iacRdsNewPath}'"
							} else {
							  sh "sed -i '/[[:space:]]*parameters[[:space:]]*=/c\\ parameters = [\n    {\n      apply_method = \"pending-reboot\"\n      name = \"rds.force_ssl\"\n      value = \"0\"\n    },\n' '${iacRdsNewPath}'"
							}
						}
						//Handle non existent parameters


						sh """
							sed -i "/[[:space:]]*source[[:space:]]*=/c\\ source = \\"${sourceModuleVersion}\\"" "${iacRdsNewPath}"
							sed -i "0,/[[:space:]]*service_group[[:space:]]*=/c\\ service_group = \\"${newServiceGroup}\\"" "${iacRdsNewPath}"
							sed -i "/[[:space:]]*rds_engine_version[[:space:]]*=/c\\ rds_engine_version = \\"${postgresqlTargetVersion}\\"" "${iacRdsNewPath}"

							#This might be useful if we decide to use abcdexample template
							#sed -i "/[[:space:]]*max_allocated_storage[[:space:]]*=/c\\ max_allocated_storage = \\"${maxAllocatedStorage}\\"" "${iacRdsNewPath}"
							#sed -i \
							#-e "/[[:space:]]*ssm_rds_username_path[[:space:]]*=/s/postgres/postgres16/" \
							#-e "/[[:space:]]*ssm_rds_password_path[[:space:]]*=/s/postgres/postgres16/" \
							#"${iacRdsNewPath}"
							
							cat "${iacRdsNewPath}"

							#To-Do: Do we need to change the route53 record or let it overwrite current value so we dont need to switch?
							# Possible error during terraform apply(?)
						"""

						dir("${iacRdsNewDir}") {
							sh """
							terragrunt hclfmt
							diff "${iacRdsPath}" "${iacRdsNewPath}" || true
							"""
						}
					}
				}
			}


			stage('Commit and Push IaC Repo'){
				// BB_BRANCH_NAME = "jenkins-${awsAccountId}-${targetEnvironmentName}-deploy-psql16-${timeStamp}"
				if(params.skipRDSProvisioning) {
					echo "Skipping RDS provisioning..."

				} else {
					BB_BRANCH_NAME = "automated-pipeline-upgrade-rds16-${targetEnvironmentName}-${serviceGroup}"
                    def commitMsg = "feat: provision rds postgresql16 ${targetEnvironmentName} ${serviceGroup}"

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
					:: Upgrade RDS to PostgreSQL 16\n \n
					"""
					withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
						// POST
						def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
						def postData =  [
							title: "feat: upgrade rds to postgresql 16 ${targetEnvironmentName}",
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
