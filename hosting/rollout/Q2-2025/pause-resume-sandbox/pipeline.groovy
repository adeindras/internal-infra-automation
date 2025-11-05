// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
    [
        parameters([
            choice(name: 'ACTION', choices: ['PAUSE', 'RESUME'], description: 'Whether want to start or stop resources'),
            booleanParam(name: 'WITH_AGS', defaultValue: false, description: '	All pods of AGS Services'),
            booleanParam(name: 'WITH_INGRESS', defaultValue: false, description: 'emissary-ingress, ALB Controller, All ingresses'),
            booleanParam(name: 'WITH_CORE_INFRA', defaultValue: false, description: 'flux-system, kube-system, karpenter, linkerd'),
            booleanParam(name: 'WITH_OBSERVABILITY', defaultValue: false, description: 'otelcollector, monitoring, and logging'),
            booleanParam(name: 'WITH_RDS', defaultValue: false, description: 'RDS Instance, RDS Snapshots'),
            booleanParam(name: 'WITH_DOCDB', defaultValue: false, description: 'DocDB Instance, DocDB Snapshots'),
            booleanParam(name: 'WITH_MSK', defaultValue: false, description: 'MSK Brokers'),
            booleanParam(name: 'WITH_ELASTICACHE', defaultValue: false, description: 'Redis Elasticache'),
            booleanParam(name: 'WITH_UTILITY', defaultValue: false, description: 'Jumpbox, forwarder')
        ])
    ]
)

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

  environment {
    AWS_CONFIG_FILE="${WORKSPACE}/aws.config"
  }

  stages {
    stage('Read Environment Information') {
      steps {
        script {
          env.CLUSTER_NAME = "sandbox-justice-dev"
          env.REGION_NAME = "us-east-2"
          env.CUSTOMER_NAME = "sandbox"
          env.PROJECT_NAME = "justice"
          env.ENVIRONMENT_NAME = "dev"
          env.AWS_ACCOUNT = "455912570532"
        }
      }
    }

    stage('Preparation') {
      steps {
        dir('iac') {
          checkout (
            changelog: false,
            poll: false,
            scm: scmGit(
              branches: [[name: "master"]],
              browser: bitbucket('https://bitbucket.org/accelbyte/iac'),
              extensions: [cloneOption(noTags: true)],
              userRemoteConfigs: [[credentialsId: 'Bitbucket_Build_EPP', url: 'https://engineering-platform-product@bitbucket.org/accelbyte/iac.git']]
            )
          )
        }

        // Configure Default Role
        sh 'echo "[default]" > aws.config'
        sh 'aws configure set web_identity_token_file $AWS_WEB_IDENTITY_TOKEN_FILE --profile default'
        sh 'aws configure set role_arn $AWS_ROLE_ARN --profile default'

        // Configure Automation Platform Role
        sh "aws configure --profile ${CLUSTER_NAME}-automation set role_arn arn:aws:iam::455912570532:role/${CLUSTER_NAME}-automation-platform"
        sh "aws configure --profile ${CLUSTER_NAME}-automation set source_profile default"

        // Configure Automation Platform Terraform Role
        sh "aws configure --profile ${CLUSTER_NAME}-automation-terraform set role_arn arn:aws:iam::455912570532:role/${CLUSTER_NAME}-automation-platform-terraform"
        sh "aws configure --profile ${CLUSTER_NAME}-automation-terraform set source_profile ${CLUSTER_NAME}-automation"

        // Set AWS profile to target environment profile from now on.
        script {
          env.AWS_PROFILE = "${CLUSTER_NAME}-automation-terraform"

        }
        // Configure kubeconfig
        sh "aws eks --region ${REGION_NAME} --profile ${CLUSTER_NAME}-automation-terraform update-kubeconfig --name ${CLUSTER_NAME}"
      }
    }

    stage("Resume/Pause Infras") {
      steps {
        script {
          parallel(
            "RDS": {
              if (params.WITH_RDS == true) {
                dir("$WORKSPACE/hosting/rollout/Q2-2025/pause-resume-sandbox") {
                  sh "bash ./scripts/with_rds.sh"
                }
              } else {
                echo "Skipping RDS resume: WITH_RDS not selected"
              }
            },
            "DOCDB": {
              if (params.WITH_DOCDB == true) {
                dir("$WORKSPACE/hosting/rollout/Q2-2025/pause-resume-sandbox") {
                  sh "bash ./scripts/with_docdb.sh"
                }
              } else {
                echo "Skipping DocDB resume: WITH_DOCDB not selected"
              }
            },
            "MSK": {
              if (params.WITH_MSK == true) {
                dir("$WORKSPACE/hosting/rollout/Q2-2025/pause-resume-sandbox") {
                  sh "bash ./scripts/with_msk.sh"
                }
              } else {
                echo "Skipping MSK resume: WITH_MSK not selected"
              }
            },
            "ELASTICACHE": {
              if (params.WITH_ELASTICACHE == true) {
                dir("$WORKSPACE/hosting/rollout/Q2-2025/pause-resume-sandbox") {
                  sh "bash ./scripts/with_elasticache.sh"
                }
              } else {
                echo "Skipping Elasticache resume: WITH_ELASTICACHE not selected"
              }
            }
          )
        }
      }
    }

    stage('Resume Services') {
      when { expression { params.ACTION == 'RESUME' } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/pause-resume-sandbox") {
          script {
            def executed = false

            if (params.WITH_CORE_INFRA) {
              echo "Resuming core infra"
              sh "bash ./scripts/with_core_infra.sh"
              executed = true
            }

            if (params.WITH_INGRESS) {
              echo "Resuming ingress"
              sh "bash ./scripts/with_ingress.sh"
              executed = true
            }

            if (params.WITH_AGS) {
              echo "Resuming AGS"
              sh "bash ./scripts/with_ags.sh"
              executed = true
            }

            if (params.WITH_OBSERVABILITY) {
              echo "Resuming observability"
              sh "bash ./scripts/with_observabilty.sh"
              executed = true
            }

            if (params.WITH_UTILITY) {
              echo "Resuming utility"
              sh "bash ./scripts/with_utility.sh"
              executed = true
            }

            if (!executed) {
              echo "No parameters were set to true."
            }
          }
        }
      }
    }

    stage('Pause Services') {
      when { expression { params.ACTION == 'PAUSE' } }
      steps {
        dir("$WORKSPACE/hosting/rollout/Q2-2025/pause-resume-sandbox") {
          script {
            def executed = false

            if (params.WITH_UTILITY) {
              echo "Executing utility script"
              sh "bash ./scripts/with_utility.sh"
              executed = true
            }

            if (params.WITH_OBSERVABILITY) {
              echo "Executing observability script"
              sh "bash ./scripts/with_observabilty.sh"
              executed = true
            }

            if (params.WITH_AGS) {
              echo "Executing AGS script"
              sh "bash ./scripts/with_ags.sh"
              executed = true
            }

            if (params.WITH_INGRESS) {
              echo "Executing ingress script"
              sh "bash ./scripts/with_ingress.sh"
              executed = true
            }

            if (params.WITH_CORE_INFRA) {
              echo "Executing core infra script"
              sh "bash ./scripts/with_core_infra.sh"
              executed = true
            }

            if (!executed) {
              echo "No parameters were set to true."
            }
          }
        }
      }
    }
  }
}

void runTerragrunt(String command) {
  sh "tfenv install"
  sh "AWS_ROLE_ARN='' AWS_WEB_IDENTITY_TOKEN_FILE='' terragrunt ${command}"
}
