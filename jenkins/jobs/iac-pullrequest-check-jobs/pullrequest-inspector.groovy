String[] modifiedDirs

def developmentPrIdentifier = "InspectionDevelopment"
def buildStopped = false
def tempDir = "tmpdir${BUILD_NUMBER}"

String toolScriptDir = '.'

def supportedServices = [
        'custom-service-manager',
        'justice-achievement-service',
        'justice-action-service',
        'justice-basic-service',
        'justice-buildinfo-service',
        'justice-challenge-service',
        'justice-chat-service',
        'justice-cloudsave-service',
        'justice-config-promoter',
        'justice-differ',
        'justice-ds-hub-service',
        'justice-gdpr-service',
        'justice-group-service',
        'justice-iam-service',
        'justice-inventory-service',
        'justice-leaderboard-service',
        'justice-legal-service',
        'justice-lobby-server',
        'justice-lobby-server-worker',
        'justice-matchmaking',
        'justice-matchmaking-history',
        'justice-matchmaking-v2',
        'justice-odin-playerportal-app',
        'justice-platform-service',
        'justice-player',
        'justice-profanity-filter-service',
        'justice-reporting-service',
        'justice-seasonpass-service',
        'justice-session-browser-service',
        'justice-session-history',
        'justice-session-service',
        'justice-session-service-native',
        'justice-session-service-worker',
        'justice-social-service',
        'justice-ugc-service',
        'customer-theorycraft-justice-progression-service',
        'custom-progression-service',
        'customer-monorepo-service',
        'customer-nebula-service',
        'custom-shared'
    ]

supportedServicesAsString = supportedServices.join(',')

node('infra-sizing') {
    container('tool') {
        stage('Parameter Validation') {
            createBanner("STAGE: Parameter Validation..")

            identifier = sh(returnStdout: true, script: '''
                echo "${branchName}" | awk -F'-' '{print $1}'
            '''
            ).trim()

            if (WORKSPACE.contains("STABLE")) {
                if (identifier == developmentPrIdentifier) {
                    currentBuild.result = 'ABORTED'
                    buildStopped = true
                    currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
                    error('Development changes - Aborting the build.') 
                }
            } else {
                if (identifier != developmentPrIdentifier) {
                    currentBuild.result = 'ABORTED'
                    buildStopped = true
                    currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
                    error('Not development changes - Aborting the build.')
                }
            }
        }

        if (buildStopped) {
            currentBuild.result = 'NOT_BUILT'
            return
        }

        try {
            stage('Init') {
                createBanner("STAGE: Initialization..")
                currentBuild.displayName = "${branchName} - ${BUILD_NUMBER}"
                withCredentials([string(credentialsId: 'BitbucketIacAcceessTokenRO-0', variable: 'BITBUCKET_ACCESS_TOKEN')]) {
                    updateBitbucketStatus(changesCommitHash, 'INPROGRESS', BITBUCKET_ACCESS_TOKEN)
                }
            }

            stage('Preparing tools') {
                withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                    sh """
                        mkdir ${tempDir}
                        cd ${tempDir}
                        BITBUCKET_ACCESS_TOKEN=\$BitbucketToken bitbucket-downloader \
                            -d jenkins/jobs/scripts/pullrequest-check \
                            -r master \
                            -s internal-infra-automation \
                            -o \$(pwd)
                        chown -R 1000:1000 jenkins
                    """
                }

                toolScriptDir = pwd() + '/' + tempDir + '/jenkins/jobs/scripts/pullrequest-check'

                sh """
                    chmod +x ${toolScriptDir}/*.sh
                """
            }

            stage('Get Modified Directories') {
                createBanner("STAGE: Get Modified Directories..")
                withCredentials([string(credentialsId: "BitbucketIacAccessTokenRW", variable: 'bbAccessToken')]) {
                    def cmd = '''
                        set +x
                        # get latest commit from master
                        latestMasterCommitHash="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/commits/master?pagelen=1" | jq -r '.values[0].hash')"

                        # changes traversal
                        DIFF="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/diff/${changesCommitHash}..${latestMasterCommitHash}" |
                            grep -E '^\\+\\+\\+ b/' |
                            cut -d ' ' -f2- |
                            cut -c3- |
                            sort -u)"
                        echo ${DIFF}
                    '''
                    diff = sh(returnStdout: true, script: cmd).trim()
                    modifiedDirs = diff.split(' ').collect{it.trim()}.findAll{it}
                }
            }
            stage('Rules Inspection') {
                dir("checks${BUILD_NUMBER}") {
                    createBanner("STAGE: Rules Inspection..")
                    withCredentials([string(credentialsId: "BitbucketIacAccessTokenRW", variable: 'bbAccessToken')]) {
                        modifiedDirs.each { dir ->
                            if (dir.contains('live')) {
                                sh """
                                    #!/bin/bash

                                    set +x

                                    if [[ ! -f ${toolScriptDir}/script.sh ]]; then
                                        echo "script not exist"
                                        exit 1
                                    fi

                                    bash ${toolScriptDir}/script.sh "${dir}" "\${bbAccessToken}" "${changesCommitHash}" "${supportedServicesAsString}"
                                """
                            }
                        }
                    }
                    def cmd = """#!/bin/bash                                
                        main() {
                            RESULT="OK"
                            if [[ -f result.txt ]]; then
                                while read -r LINE; do
                                    if [[ \$LINE == "NOT_OK" ]]; then
                                        RESULT="NOT_OK"
                                        break
                                    fi
                                done < <(cat result.txt)
                            fi

                            if [[ \$RESULT == "OK" ]]; then
                                echo "OK"
                            else
                                echo "NOT_OK"
                            fi
                        }
                        main
                    """
                    checkStatus = sh(returnStdout: true, script: cmd).trim()
                    echo "checkStatus: ${checkStatus}"
                    switch(checkStatus) {
                        case "OK":
                            echo "PR Check is OK"
                            currentBuild.result = 'SUCCESS'
                        break
                        case "NOT_OK":
                            echo "PR Check is NOT OK"
                            currentBuild.result = 'FAILURE'
                        break
                        default:
                            echo "Error during PR Check"
                            currentBuild.result = 'ABORTED'
                        break
                    }
                }
            }
        } catch (SkipException ex) {
            currentBuild.result = 'ABORTED'
            currentBuild.displayName = "${currentBuild.displayName} - [Skipped]"
            echo "Build is skipped:\n\t${ex.message}"
        } catch (InterruptedException err) {
            currentBuild.result = 'ABORTED'
        } catch (Exception err) {
            echo "Exception Thrown:\n\t${err}"
            currentBuild.result = 'FAILURE'
            // TODO: Send notif to slack channel
        } finally {
            withCredentials([string(credentialsId: 'BitbucketIacAcceessTokenRO-0', variable: 'BITBUCKET_ACCESS_TOKEN')]) {
                updateBitbucketStatus(changesCommitHash, currentBuild.currentResult, BITBUCKET_ACCESS_TOKEN)
            }
        }
    }
}

void updateBitbucketStatus(String commitHash, status, token) {
  String bitbucketURL = "https://api.bitbucket.org/2.0/repositories"
  String bitbucketState

  // Map jenkins result to bitbucket state
  // https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/
  switch(status) {
    case "SUCCESS":
      bitbucketState = "SUCCESSFUL"
      break
    case "INPROGRESS":
      bitbucketState = "INPROGRESS"
      break
    case "FAILURE":
      bitbucketState = "FAILED"
      break
    case "ABORTED":
      bitbucketState = "STOPPED"
      break
    default:
      throw new Exception("Unknown Jenkins build status: $status")
  }

  String requestBody = """
    {
        "key": "JENKINS-PR-CHECK-${BUILD_NUMBER}",
        "name": "pull-request-merge-check",
        "state": "$bitbucketState",
        "description": "Jenkins Pull Request Merge Check",
        "url": "${BUILD_URL}console"
    }
  """

  httpRequest(
    url: "${bitbucketURL}/accelbyte/iac/commit/${commitHash}/statuses/build",
    httpMode: "POST",
    requestBody: requestBody,
    contentType: "APPLICATION_JSON",
    customHeaders: [[name: 'Authorization', value: 'Bearer ' + token]],
  )
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