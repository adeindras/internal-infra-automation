// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.
pipeline {
  agent {
    kubernetes {
      yaml '''
          apiVersion: v1
          kind: Pod
          metadata:
            namespace: jenkins
            annotations:
              karpenter.sh/do-not-disrupt: "true"
          spec:
            serviceAccountName: jenkinscd-automation-platform
            securityContext:
              runAsUser: 1000
            nodeSelector:
              karpenter.sh/capacity-type: on-demand
            containers:
              - name: jnlp
                image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.2
                resources:
                  requests:
                    memory: 1Gi
                    cpu: 500m
                  limits:
                    memory: 1Gi
        '''
    }
  }

  parameters {
      string description: 'Name of the pipeline. Can contain spaces.', name: 'PIPELINE_NAME', trim: true
      choice choices: ['Q1-2025', 'Q2-2025', 'Q3-2025'], description: 'Fiscal quarter of the maintenance', name: 'FISCAL_QUARTER'
  }


    stages {
      stage('Run') {
        steps {
          withCredentials([usernameColonPassword(credentialsId: 'jenkinscd-api-token-adinbaskoropratomo', variable: 'JENKINS_BASIC_AUTH')]) {
            sshagent(['bitbucket-repo-read-only']) {
              sh """#!/bin/bash
                TARGET_BRANCH_NAME="\$(echo '$params.PIPELINE_NAME' | tr ' ' '-' | tr '[:upper:]' '[:lower:]')-dev"

                python3 -m venv .venv
                source .venv/bin/activate
                pip install cookiecutter
                pushd hosting/rollout
                python3 ../bootstrap/generate.py ../bootstrap/template . '$params.PIPELINE_NAME' '$params.FISCAL_QUARTER'

                git checkout -b \${TARGET_BRANCH_NAME}
                git add '$params.FISCAL_QUARTER'
                git config --global user.email "build@accelbyte.net"
                git config --global user.name "Build Accelbyte"
                mkdir -p ~/.ssh && curl https://bitbucket.org/site/ssh >> ~/.ssh/known_hosts
                git remote add originssh git@bitbucket.org:accelbyte/internal-infra-automation.git
                git commit -m 'Init $params.PIPELINE_NAME pipeline'
                git push --set-upstream originssh \${TARGET_BRANCH_NAME}
                
                python3 ../bootstrap/createjob.py '$params.PIPELINE_NAME' '$params.FISCAL_QUARTER' ../bootstrap/template.xml
                popd
              """
            }
          }
        }
      }
  }
}
