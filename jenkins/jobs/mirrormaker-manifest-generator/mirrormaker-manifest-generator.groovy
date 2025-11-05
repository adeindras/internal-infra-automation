import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// todo: test under both happy and error path scenarios
// assumptions:
// - jenkins never fail
// happy path
// - mirrormaker1
//   - [ok] pr created
//   - overlay manifest generated in both old flux and new flux environment
//     - [ok] in desired target environment directory (sync/extended)
//     - [ok] handle both old and new flux manifest format
//     - with correct desired environment variables substitution
//       - [ok] topic_whitelist
//       - [ok] offset.reset.policy (is_mirror_message_from_beginning)
//       - [ok] replica_count
//       - [ok] source_msk_bootstrap_servers_ssm_path
//       - [ok] source_msk_username_ssm_path
//       - [ok] source_msk_password_ssm_path
//       - [ok] target_msk_bootstrap_servers_ssm_path
//       - [ok] target_msk_username_ssm_path
//       - [ok] target_msk_password_ssm_path
// - mirrormaker2
//   - [ok] pr created
//   - overlay manifest generated in both old flux and new flux environment
//     - [ok] in desired target environment directory (sync/extended)
//     - [ok] handle both old and new flux manifest format
//     - with correct desired environment variables substitution
//       - [ok] topic_whitelist
//       - [ok] offset.reset.policy (is_mirror_message_from_beginning)
//       - [ok] replica_count
//       - [ok] source_msk_bootstrap_servers_ssm_path
//       - [ok] source_msk_username_ssm_path
//       - [ok] source_msk_password_ssm_path
//       - [ok] target_msk_bootstrap_servers_ssm_path
//       - [ok] target_msk_username_ssm_path
//       - [ok] target_msk_password_ssm_path
// error path
// - misconfigured jenkins parameters
//   - TARGET_ENVIRONMENT_NAME
//     - [todo] is empty
//     - [todo] is not exist
//   - MIRRORMAKER_PLATFORM_KIND
//     - [todo] is empty
//     - [todo] is not valid (neither mirrormaker1 or mirrormaker2)
//   - TOPIC_WHITELIST
//     - [todo] is empty
//   - IS_MIRROR_MESSAGE_FROM_BEGINNING
//     - [todo] is empty
//     - [todo] is not valid (neither yes or no)
//   - REPLICA_COUNT
//     - [todo] is empty
//     - [todo] is not valid (not number)
//   - SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH
//     - [todo] is empty
//   - SOURCE_MSK_USERNAME_SSM_PATH
//     - [todo] is empty
//   - SOURCE_MSK_PASSWORD_SSM_PATH
//     - [todo] is empty
//   - TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH
//     - [todo] is empty
//   - TARGET_MSK_USERNAME_SSM_PATH
//     - [todo] is empty
//   - TARGET_MSK_PASSWORD_SSM_PATH
//     - [todo] is empty
// - [todo] no changes, so no create pr
// - [todo] failed to commit
// - [todo] failed to push
// - [todo] failed to create pr
// - [todo] failed to send slack notif

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(name: "TARGET_ENVIRONMENT_NAME", choices: envList, description: "Target environment name for mirrormaker to deploy."),
            choice(name: "MIRRORMAKER_PLATFORM_KIND", choices: ['mirrormaker1', 'mirrormaker2'], description: "MirrorMaker platform kind to deploy. (mirrormaker1 or mirrormaker2)"),
            string(name: "TOPIC_WHITELIST", defaultValue: "blank", description: "Comma-separated list of topics to mirror."),
            choice(name: "IS_MIRROR_MESSAGE_FROM_BEGINNING", choices: ['yes', 'no'], description: "A choice to mirror messages from beginning or latest. (yes or no)"),
            choice(name: "REPLICA_COUNT", choices: ['1', '2', '3', '4', '5'], description: "Number of MirrorMaker pods to deploy. (1-5)"),

            string(name: "SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH", defaultValue: "blank", description: "SSM Parameter Store path for Source MSK brokers."),
            string(name: "SOURCE_MSK_USERNAME_SSM_PATH", defaultValue: "blank", description: "SSM Parameter Store path for Source MSK username."),
            string(name: "SOURCE_MSK_PASSWORD_SSM_PATH", defaultValue: "blank", description: "SSM Parameter Store path for Source MSK password."),

            string(name: "TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH", defaultValue: "blank", description: "SSM Parameter Store path for Target MSK brokers."),
            string(name: "TARGET_MSK_USERNAME_SSM_PATH", defaultValue: "blank", description: "SSM Parameter Store path for Target MSK username."),
            string(name: "TARGET_MSK_PASSWORD_SSM_PATH", defaultValue: "blank", description: "SSM Parameter Store path for Target MSK password.")
        ])
    ]
)

def scalingBlueprintGetData(endpoint, apiKey) {
    def uri = "http://internal-scaling-blueprint.devportal/scalingblueprint" + endpoint
    def get = new URL(uri).openConnection();
    get.setRequestProperty ("Authorization", "Bearer " + apiKey);
    def getRC = get.getResponseCode();
    println(getRC);
    if(getRC.equals(200)) {
        def reply = get.getInputStream().getText()
        return reply
    }
    return null
}

def getEnvironmentList() {
    envs = []
    withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
        r = scalingBlueprintGetData("/admin/v1/clusters", ScalingBlueprintAPIKey)
        if ( r != null) {
            def jsonSlurper = new JsonSlurper()
            def replyMap = jsonSlurper.parseText(r)
            envs = replyMap.clusterNames
        }
    }

    if (!envs.find()) {
        envs.push("Error getting env list data")
    } else {
        return envs
    }
}

// constants
def targetEnvironmentName = params.TARGET_ENVIRONMENT_NAME
def mirrormakerPlatformKind = params.MIRRORMAKER_PLATFORM_KIND
def topicWhitelist = params.TOPIC_WHITELIST
def isMirrorMessageFromBeginning = params.IS_MIRROR_MESSAGE_FROM_BEGINNING
def replicaCount = params.REPLICA_COUNT

def sourceMskBootstrapServersSsmPath = params.SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH
def sourceMskUsernameSsmPath = params.SOURCE_MSK_USERNAME_SSM_PATH
def sourceMskPasswordSsmPath = params.SOURCE_MSK_PASSWORD_SSM_PATH

def targetMskBootstrapServersSsmPath = params.TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH
def targetMskUsernameSsmPath = params.TARGET_MSK_USERNAME_SSM_PATH
def targetMskPasswordSsmPath = params.TARGET_MSK_PASSWORD_SSM_PATH

def identifier = new Date().getTime().toString()

def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def prHtmlLink = ""
def prLink
def slackThread
def offsetResetPolicy

def (customer, project, environment) = targetEnvironmentName.split('-')
def envManifestDirectory
def branchName
def commitMessage
def hasChanges

def isNewFlux
def overlayManifestName
def mirrormaker1OverlayManifestName = "rightsize-kafka-mirrormaker1.yaml"
def mirrormaker2OverlayManifestName = "rightsize-kafka-mirrormaker2.yaml"

node('infra-sizing') {
    container('tool') {
        stage('Initializing') {
            createBanner("STAGE: Initializing.. sending status notif to slack")

            withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                // POST
                def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']
                def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                def postData =  [
                    channel: "C079A11910R",
                    blocks: [
                        [
                            type: "section",
                            text: [
                                type: "mrkdwn",
                                text: "You have a new mirrormaker deployment request triggered by ${userId}:\n*<${BUILD_URL}console|Go to Jenkins now!>*"
                            ]
                        ],
                        [
                            type: "section",
                            fields: [
                                [
                                    type: "mrkdwn",
                                    text: "*Environment:*\n${targetEnvironmentName}"
                                ],
                                [
                                    type: "mrkdwn",
                                    text: "*Triggered by:*\n${userId}"
                                ],
                            ]
                        ]
                    ]
                ]
                def jsonPayload = JsonOutput.toJson(postData)
                post.setRequestMethod("POST")
                post.setDoOutput(true)
                post.setRequestProperty("Content-Type", "application/json")
                post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                def postRC = post.getResponseCode();
                println(postRC);
                if(postRC.equals(200) || postRC.equals(201)) {
                    def jsonSlurper = new JsonSlurper()
                    def reply = post.getInputStream().getText()
                    def replyMap = jsonSlurper.parseText(reply)
                    slackThread = replyMap.ts
                }
            }
        }

        stage('Parameter Validation') {
            createBanner("STAGE: Parameter Validation")

            if (targetEnvironmentName == '' || targetEnvironmentName == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "TARGET_ENVIRONMENT_NAME: ${targetEnvironmentName}"
                error('Aborting the build')
            }

            // todo: validate targetEnvironmentName

            if (!customer || !project || !environment) {
                currentBuild.result = 'FAILURE'
                buildStopped = true
                error('Aborting the build')
            }

            if (mirrormakerPlatformKind == '' || mirrormakerPlatformKind == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "MIRRORMAKER_PLATFORM_KIND: ${mirrormakerPlatformKind}"
                error('Aborting the build')
            }

            if (mirrormakerPlatformKind != "mirrormaker1" && mirrormakerPlatformKind != "mirrormaker2") {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "MIRRORMAKER_PLATFORM_KIND: ${mirrormakerPlatformKind}"
                error('Aborting the build')
            }

            if (topicWhitelist == '' || topicWhitelist == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "TOPIC_WHITELIST: ${topicWhitelist}"
                error('Aborting the build')
            }

            if (isMirrorMessageFromBeginning == '' || isMirrorMessageFromBeginning == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "IS_MIRROR_MESSAGE_FROM_BEGINNING: ${isMirrorMessageFromBeginning}"
                error('Aborting the build')
            }

            if (isMirrorMessageFromBeginning != "yes" && isMirrorMessageFromBeginning != "no") {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "IS_MIRROR_MESSAGE_FROM_BEGINNING: ${isMirrorMessageFromBeginning}"
                error('Aborting the build')
            }

            if (replicaCount == '' || replicaCount == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "REPLICA_COUNT: ${replicaCount}"
                error('Aborting the build')
            }

            // todo: validate replicaCount

            if (sourceMskBootstrapServersSsmPath == '' || sourceMskBootstrapServersSsmPath == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH: ${sourceMskBootstrapServersSsmPath}"
                error('Aborting the build')
            }

            if (sourceMskUsernameSsmPath == '' || sourceMskUsernameSsmPath == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "SOURCE_MSK_USERNAME_SSM_PATH: ${sourceMskUsernameSsmPath}"
                error('Aborting the build')
            }

            if (sourceMskPasswordSsmPath == '' || sourceMskPasswordSsmPath == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "SOURCE_MSK_PASSWORD_SSM_PATH: ${sourceMskPasswordSsmPath}"
                error('Aborting the build')
            }

            if (targetMskBootstrapServersSsmPath == '' || targetMskBootstrapServersSsmPath == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH: ${targetMskBootstrapServersSsmPath}"
                error('Aborting the build')
            }

            if (targetMskUsernameSsmPath == '' || targetMskUsernameSsmPath == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "TARGET_MSK_USERNAME_SSM_PATH: ${targetMskUsernameSsmPath}"
                error('Aborting the build')
            }

            if (targetMskPasswordSsmPath == '' || targetMskPasswordSsmPath == 'blank') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                echo "TARGET_MSK_PASSWORD_SSM_PATH: ${targetMskPasswordSsmPath}"
                error('Aborting the build')
            }

            echo "TARGET_ENVIRONMENT_NAME: ${targetEnvironmentName}"
            echo "MIRRORMAKER_PLATFORM_KIND: ${mirrormakerPlatformKind}"
            echo "TOPIC_WHITELIST: ${topicWhitelist}"
            echo "IS_MIRROR_MESSAGE_FROM_BEGINNING: ${isMirrorMessageFromBeginning}"
            echo "REPLICA_COUNT: ${replicaCount}"

            echo "SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH: ${sourceMskBootstrapServersSsmPath}"
            echo "SOURCE_MSK_USERNAME_SSM_PATH: ${sourceMskUsernameSsmPath}"
            echo "SOURCE_MSK_PASSWORD_SSM_PATH: ${sourceMskPasswordSsmPath}"

            echo "TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH: ${targetMskBootstrapServersSsmPath}"
            echo "TARGET_MSK_USERNAME_SSM_PATH: ${targetMskUsernameSsmPath}"
            echo "TARGET_MSK_PASSWORD_SSM_PATH: ${targetMskPasswordSsmPath}"
        }

        if (buildStopped) {
            createBanner("ERROR: Something went wrong during parameter validation..")

            currentBuild.displayName = "#${BUILD_NUMBER} - NOT_BUILT"

            withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                // POST
                def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                def postData =  [
                    channel: "C079A11910R",
                    blocks: [
                        [
                            type: "section",
                            text: [
                                type: "mrkdwn",
                                text: ":alert: Something went wrong during parameter validation:\n*<${BUILD_URL}/console|Go to Jenkins now!>*"
                            ]
                        ]
                    ],
                    thread_ts: "${slackThread}"
                ]
                def jsonPayload = JsonOutput.toJson(postData)
                post.setRequestMethod("POST")
                post.setDoOutput(true)
                post.setRequestProperty("Content-Type", "application/json")
                post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                def postRC = post.getResponseCode();
                println(postRC);
                if(postRC.equals(200) || postRC.equals(201)) {
                    println(post.getInputStream().getText())
                }
            }
        } else {
            currentBuild.displayName = "#${BUILD_NUMBER} - ${targetEnvironmentName}"

            stage('Clone IaC Repo') {
                createBanner("STAGE: Cloning IaC Repository")

                dir(tempDir) {
                    sshagent(['bitbucket-repo-read-only']) {
                        sh """
                            GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
                            git clone --depth 1 --branch=master --quiet "git@bitbucket.org:accelbyte/iac.git"
                            chown -R 1000:1000 iac
                        """
                    }
                }

                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: "C079A11910R",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: ":white_check_mark: Clone IaC Repo Stage is completed"
                                ]
                            ]
                        ],
                        thread_ts: "${slackThread}"
                    ]
                    def jsonPayload = JsonOutput.toJson(postData)
                    post.setRequestMethod("POST")
                    post.setDoOutput(true)
                    post.setRequestProperty("Content-Type", "application/json")
                    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                    def postRC = post.getResponseCode();
                    println(postRC);
                    if(postRC.equals(200) || postRC.equals(201)) {
                        println(post.getInputStream().getText())
                    }
                }
            }

            stage('Prepare Variables') {
                createBanner("STAGE: Preparing Variables")

                if (isMirrorMessageFromBeginning == "yes") {
                    offsetResetPolicy = "earliest"
                } else {
                    offsetResetPolicy = "latest"
                }

                if (mirrormakerPlatformKind == "mirrormaker1") {
                    overlayManifestName = mirrormaker1OverlayManifestName
                }

                if (mirrormakerPlatformKind == "mirrormaker2") {
                    overlayManifestName = mirrormaker2OverlayManifestName
                }

                branchName = "feat-${targetEnvironmentName}-${mirrormakerPlatformKind}-deployment-${identifier}"
                commitMessage = "feat: deploy ${mirrormakerPlatformKind} to ${targetEnvironmentName}"

                dir(tempDir) {
                    dir("iac") {
                        envManifestDirectory = sh(
                            returnStdout: true,
                            script: """
                                clusterDir=\$(find ./manifests -path "*/${customer}/${project}/*" -type d -name "cluster-variables" | grep "${environment}\\/")
                                dirname \${clusterDir}
                            """
                        ).trim()
                    }
                }

                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: "C079A11910R",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: ":white_check_mark: Variables Preparation Stage is completed"
                                ]
                            ]
                        ],
                        thread_ts: "${slackThread}"
                    ]
                    def jsonPayload = JsonOutput.toJson(postData)
                    post.setRequestMethod("POST")
                    post.setDoOutput(true)
                    post.setRequestProperty("Content-Type", "application/json")
                    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                    def postRC = post.getResponseCode();
                    println(postRC);
                    if(postRC.equals(200) || postRC.equals(201)) {
                        println(post.getInputStream().getText())
                    }
                }
            }

            stage('Generate Overlay Manifests from Template') {
                createBanner("STAGE: Generate Overlay Manifests from Template")

                // todo: add manifest generation logic
                // - determine if it's old flux (sourceRef: iac-repo) or new flux (sourceRef: flux-system)
                // - download overlay template
                // - put to the target environment directory

                dir(tempDir) {
                    dir("iac") {
                        // determine if it's old flux or new flux
                        isNewFlux = sh(
                            returnStdout: true,
                            script: """
                                if [[ -f ${envManifestDirectory}/cluster-system/infrastructure.yaml ]]; then
                                    echo "true"
                                else
                                    echo "false"
                                fi
                            """
                        ).trim()
                    }
                }

                if (mirrormakerPlatformKind == "mirrormaker1") {
                    // strategy:
                    // 1. download overlay template
                    // 2. put into ${envManifestDirectory}/sync/extended/${overlayManifestName}
                    // 3. append "./${overlayManifestName}" into ${envManifestDirectory}/sync/extended/kustomization.yaml in ".bases[]"
                    // 4. modify ${envManifestDirectory}/sync/extended/${overlayManifestName} accordingly

                    // download overlay template
                    if (isNewFlux == "true") {
                        withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                            sh """
                                BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                    -f jenkins/jobs/mirrormaker-manifest-generator/mm1-new-flux-overlay-template.yaml \
                                    -r master \
                                    -s internal-infra-automation \
                                    -o /tmp/${BUILD_NUMBER}
                            """
                        }

                        // put into ${envManifestDirectory}/sync/extended/${overlayManifestName}
                        dir(tempDir) {
                            dir("iac") {
                                sh """
                                    cp /tmp/${BUILD_NUMBER}/jenkins/jobs/mirrormaker-manifest-generator/mm1-new-flux-overlay-template.yaml \
                                        ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                """
                            }
                        }
                    }

                    if (isNewFlux == "false") {
                        withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                            sh """
                                BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                    -f jenkins/jobs/mirrormaker-manifest-generator/mm1-old-flux-overlay-template.yaml \
                                    -r master \
                                    -s internal-infra-automation \
                                    -o /tmp/${BUILD_NUMBER}
                            """
                        }

                        // put into ${envManifestDirectory}/sync/extended/${overlayManifestName}
                        dir(tempDir) {
                            dir("iac") {
                                sh """
                                    cp /tmp/${BUILD_NUMBER}/jenkins/jobs/mirrormaker-manifest-generator/mm1-old-flux-overlay-template.yaml \
                                        ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                """
                            }
                        }
                    }

                    dir(tempDir) {
                        dir("iac") {
                            // append "./${overlayManifestName}" into ${envManifestDirectory}/sync/extended/kustomization.yaml in ".bases[]"
                            sh """
                                yq e '.bases += \"./${overlayManifestName}\"' -i ${envManifestDirectory}/sync/extended/kustomization.yaml
                            """

                            // modify overlay manifest accordingly
                            sh """
                                yq -i '.spec.postBuild.substitute.TOPIC_WHITELIST = "${topicWhitelist}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.OFFSET_RESET_POLICY = "${offsetResetPolicy}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.REPLICA_COUNT = "${replicaCount}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH = "${sourceMskBootstrapServersSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.SOURCE_MSK_SASL_USERNAME_SSM_PATH = "${sourceMskUsernameSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.SOURCE_MSK_SASL_PASSWORD_SSM_PATH = "${sourceMskPasswordSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH = "${targetMskBootstrapServersSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.TARGET_MSK_SASL_USERNAME_SSM_PATH = "${targetMskUsernameSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.TARGET_MSK_SASL_PASSWORD_SSM_PATH = "${targetMskPasswordSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                            """
                        }
                    }
                }

                if (mirrormakerPlatformKind == "mirrormaker2") {
                    // strategy:
                    // 1. download overlay template
                    // 2. put into ${envManifestDirectory}/sync/extended/${overlayManifestName}
                    // 3. append "./${overlayManifestName}" into ${envManifestDirectory}/sync/extended/kustomization.yaml in ".bases[]"
                    // 4. modify ${envManifestDirectory}/sync/extended/${overlayManifestName} accordingly

                    // download overlay template
                    if (isNewFlux == "true") {
                        withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                            sh """
                                BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                    -f jenkins/jobs/mirrormaker-manifest-generator/mm2-new-flux-overlay-template.yaml \
                                    -r master \
                                    -s internal-infra-automation \
                                    -o /tmp/${BUILD_NUMBER}
                            """
                        }

                        // put into ${envManifestDirectory}/sync/extended/${overlayManifestName}
                        dir(tempDir) {
                            dir("iac") {
                                sh """
                                    cp /tmp/${BUILD_NUMBER}/jenkins/jobs/mirrormaker-manifest-generator/mm2-new-flux-overlay-template.yaml \
                                        ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                """
                            }
                        }
                    }

                    if (isNewFlux == "false") {
                        withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                            sh """
                                BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                    -f jenkins/jobs/mirrormaker-manifest-generator/mm2-old-flux-overlay-template.yaml \
                                    -r master \
                                    -s internal-infra-automation \
                                    -o /tmp/${BUILD_NUMBER}
                            """
                        }

                        // put into ${envManifestDirectory}/sync/extended/${overlayManifestName}
                        dir(tempDir) {
                            dir("iac") {
                                sh """
                                    cp /tmp/${BUILD_NUMBER}/jenkins/jobs/mirrormaker-manifest-generator/mm2-old-flux-overlay-template.yaml \
                                        ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                """
                            }
                        }
                    }

                    dir(tempDir) {
                        dir("iac") {
                            // append "./${overlayManifestName}" into ${envManifestDirectory}/sync/extended/kustomization.yaml in ".bases[]"
                            sh """
                                yq e '.bases += \"./${overlayManifestName}\"' -i ${envManifestDirectory}/sync/extended/kustomization.yaml
                            """

                            // modify overlay manifest accordingly
                            sh """
                                yq -i '.spec.postBuild.substitute.TOPIC_WHITELIST = "${topicWhitelist}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.OFFSET_RESET_POLICY = "${offsetResetPolicy}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.REPLICA_COUNT = "${replicaCount}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.SOURCE_MSK_BOOTSTRAP_SERVERS_SSM_PATH = "${sourceMskBootstrapServersSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.SOURCE_MSK_SASL_USERNAME_SSM_PATH = "${sourceMskUsernameSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.SOURCE_MSK_SASL_PASSWORD_SSM_PATH = "${sourceMskPasswordSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.TARGET_MSK_BOOTSTRAP_SERVERS_SSM_PATH = "${targetMskBootstrapServersSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.TARGET_MSK_SASL_USERNAME_SSM_PATH = "${targetMskUsernameSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                                yq -i '.spec.postBuild.substitute.TARGET_MSK_SASL_PASSWORD_SSM_PATH = "${targetMskPasswordSsmPath}"' ${envManifestDirectory}/sync/extended/${overlayManifestName}
                            """
                        }
                    }
                }

                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: "C079A11910R",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: ":white_check_mark: Generate Overlay Manifests from Template Stage is completed"
                                ]
                            ]
                        ],
                        thread_ts: "${slackThread}"
                    ]
                    def jsonPayload = JsonOutput.toJson(postData)
                    post.setRequestMethod("POST")
                    post.setDoOutput(true)
                    post.setRequestProperty("Content-Type", "application/json")
                    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                    def postRC = post.getResponseCode();
                    println(postRC);
                    if(postRC.equals(200) || postRC.equals(201)) {
                        println(post.getInputStream().getText())
                    }
                }
            }

            stage('Commit Changes & Push to Bitbucket') {
                createBanner("STAGE: Commit Changes & Push to Bitbucket")

                dir(tempDir) {
                    dir("iac") {
                        sh "git config --global --add safe.directory '*'"
                        hasChanges = sh(
                            returnStdout: true,
                            script: """
                                if [[ -z \$(git status -s) ]]; then
                                    echo "false"
                                else
                                    echo "true"
                                fi
                            """).trim()

                        if (hasChanges == "true") {
                            sshagent(['bitbucket-repo-read-only']) {
                                sh """
                                    git config --global user.email "build@accelbyte.net"
                                    git config --global user.name "Build AccelByte"
                                    git checkout -b ${branchName}
                                    git add .
                                    git commit -m "${commitMessage}"
                                    GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" git push origin ${branchName}
                                """
                            }

                            withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                                // POST
                                def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                                def postData =  [
                                    channel: "C079A11910R",
                                    blocks: [
                                        [
                                            type: "section",
                                            text: [
                                                type: "mrkdwn",
                                                text: ":white_check_mark: Commit Changes & Push to Bitbucket Stage is completed with changes committed to branch name: ${branchName}"
                                            ]
                                        ]
                                    ],
                                    thread_ts: "${slackThread}"
                                ]
                                def jsonPayload = JsonOutput.toJson(postData)
                                post.setRequestMethod("POST")
                                post.setDoOutput(true)
                                post.setRequestProperty("Content-Type", "application/json")
                                post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                                post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                                def postRC = post.getResponseCode();
                                println(postRC);
                                if(postRC.equals(200) || postRC.equals(201)) {
                                    println(post.getInputStream().getText())
                                }
                            }
                        } else {
                            println("No changes. No commit needed.");

                            withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                                // POST
                                def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                                def postData =  [
                                    channel: "C079A11910R",
                                    blocks: [
                                        [
                                            type: "section",
                                            text: [
                                                type: "mrkdwn",
                                                text: ":white_check_mark: Commit Changes & Push to Bitbucket Stage is completed with no commit needed"
                                            ]
                                        ]
                                    ],
                                    thread_ts: "${slackThread}"
                                ]
                                def jsonPayload = JsonOutput.toJson(postData)
                                post.setRequestMethod("POST")
                                post.setDoOutput(true)
                                post.setRequestProperty("Content-Type", "application/json")
                                post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                                post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                                def postRC = post.getResponseCode();
                                println(postRC);
                                if(postRC.equals(200) || postRC.equals(201)) {
                                    println(post.getInputStream().getText())
                                }
                            }
                        }
                        }
                    }
                }

            if (hasChanges == "true") {
                stage ('Create Pull Request') {
                    createBanner("STAGE: Create Pull Request")

                    prSummary = "Deploy ${mirrormakerPlatformKind} to ${targetEnvironmentName} - triggered from Jenkins"
                    withCredentials([string(credentialsId: "BitbucketAppKeyUserPassB64", variable: 'BitbucketAppKeyUserPassB64')]) {
                        // POST
                        def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
                        def postData =  [
                            title: "feat: ${mirrormakerPlatformKind} Deployment to ${targetEnvironmentName} - triggered from Jenkins",
                            source: [
                                branch: [
                                    name: "${branchName}"
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
                        post.setRequestProperty("Authorization", "Basic ${BitbucketAppKeyUserPassB64}")
                        post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                        def postRC = post.getResponseCode();
                        if(postRC.equals(200) || postRC.equals(201)) {
                            def jsonSlurper = new JsonSlurper()
                            def reply = post.getInputStream().getText()
                            def replyMap = jsonSlurper.parseText(reply)
                            prHtmlLink = replyMap.links.html.href
                        }
                        println(prHtmlLink);
                    }

                    prLink="<${prHtmlLink}/console|Check PR!>"

                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        // POST
                        def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                        def postData =  [
                            channel: "C079A11910R",
                            blocks: [
                                [
                                    type: "section",
                                    text: [
                                        type: "mrkdwn",
                                        text: ":white_check_mark: Create Pull Request Stage is completed with PR link: ${prLink}"
                                    ]
                                ]
                            ],
                            thread_ts: "${slackThread}"
                        ]
                        def jsonPayload = JsonOutput.toJson(postData)
                        post.setRequestMethod("POST")
                        post.setDoOutput(true)
                        post.setRequestProperty("Content-Type", "application/json")
                        post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                        post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                        def postRC = post.getResponseCode();
                        println(postRC);
                        if(postRC.equals(200) || postRC.equals(201)) {
                            println(post.getInputStream().getText())
                        }
                    }
                }
            }
        }
    }
}

// Hacky way to skip later stages
public class SkipException extends Exception {
    public SkipException(String errorMessage) {
        super(errorMessage);
    }
}

void createBanner(String message) {
    ansiColor('xterm'){
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
        echo '\033[1;4;33m' + ":: ${message}"
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    }
}