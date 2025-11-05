import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String pipelineRerunDelay = params.pipelineRerunDelay
def tempDir = "wiz-tmpdir${BUILD_NUMBER}"
currentBuild.displayName = "#${BUILD_NUMBER}"

node('hosting-agent') {
	container('tool') {
		dir(tempDir) {
			stage('Preparing Wiz') {
				// Getting wiz-cli package and authentication
				sh "curl --silent -o wizcli https://downloads.wiz.io/wizcli/latest/wizcli-linux-amd64 && chmod +x wizcli"
				sh "./wizcli version"
				withCredentials([
					usernamePassword(credentialsId: 'iac-wiz-service-account', usernameVariable: 'CLIENT_ID', passwordVariable: 'CLIENT_SECRET')
					]) {
						sh './wizcli auth --id $CLIENT_ID --secret $CLIENT_SECRET'
					}
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

			stage('Scanning IAC Modules') {
				sh '''
					./wizcli iac scan --path iac/modules --name "IAC Modules Weekly Scan $(date -u +"%d-%m-%Y")" --output modules-iac.json,json,true --tag "weekly,jenkins,modules"
				'''
			}

			stage('Scanning IAC Code') {
				sh '''
					./wizcli iac scan --path iac/live --name "IAC Code Weekly Scan $(date -u +"%d-%m-%Y")" --output live-iac.json,json,true --tag "weekly,jenkins,code"
				'''
			}
		}
	}
}