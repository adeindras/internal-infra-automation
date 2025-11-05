import groovy.json.JsonOutput
import groovy.json.JsonSlurper

pipeline {
    agent none
    triggers {
        cron('H 18 * * 6')
    }
    stages {
        stage('Run Bash Script') {
            agent {
                node {
                    label 'deploy-agent'
                }
            }
            steps {
                script {
                  withCredentials([string(credentialsId: 'RundeckHostingToken', variable: 'RundeckHostingToken')]) {
                    sh """
                    #!/bin/bash
                      curl --location 'https://rundeck.prod.devportal.accelbyte.io/api/14/job/5e834bee-4f7b-4709-9011-88d799c88f38/run' \
                      --header 'Accept: application/json' \
                      --header 'Content-Type: application/json' \
                      --header 'X-Rundeck-Auth-Token: $RundeckHostingToken' \
                      --data '{
                          "argstring": "-bb_branch development -slack_channel_id C07C69NHGTW",
                          "filter": "tags:iam_terraform"
                      }'
                    """
                  }
                }
            }
        }
    }
}