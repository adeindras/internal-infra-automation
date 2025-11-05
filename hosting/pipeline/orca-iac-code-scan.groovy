import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String pipelineRerunDelay = params.pipelineRerunDelay
def tempDir = "orca-tmpdir${BUILD_NUMBER}"
currentBuild.displayName = "#${BUILD_NUMBER}"

node('hosting-agent') {
	container('tool') {
		dir(tempDir) {
			stage('Preparing Orca') {
                // Getting orca-cli package
                sh "curl -sfL 'https://raw.githubusercontent.com/orcasecurity/orca-cli/main/install.sh' | sh"
                sh "orca-cli -v"
			}

			stage('Clone iac repository') {
				sshagent(['bitbucket-repo-read-only']) {
					// Clone IAC repo
					sh """#!/bin/bash
						set -e
						export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
						git clone --quiet "git@bitbucket.org:accelbyte/iac.git" || true
						chmod -R 777 iac || true
						rm -rf ~/.aws/config || true
					"""
				}
			}

            withCredentials([
                string(credentialsId: "iac-orca-token", variable: 'ORCA_TOKEN')
            ]){
                stage('Orca IAC Modules Scan'){
                    dir('iac'){
                        sh (" orca-cli iac scan -p iac --format cli --path modules/ --api-token ${ORCA_TOKEN} --display-name iac-weekly-scan/modules --labels weekly,iac,jenkins || true ")
					}
                }
                stage('Orca IAC Code Scan'){
                    dir('iac'){
						sh (" orca-cli iac scan -p iac --format cli --path live/ --api-token ${ORCA_TOKEN} --display-name iac-weekly-scan/live --labels weekly,iac,jenkins || true ")
					}
                }
                stage('Orca IAC Secret Scan'){
                	sh (" orca-cli secrets scan -p iac --format table --path iac/ --api-token ${ORCA_TOKEN} --display-name iac-weekly-scan/iac-secrets --labels weekly,secrets,jenkins || true ")
                }      
            }
        }
    }
}