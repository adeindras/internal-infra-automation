import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: envList, description: "Target environment name", name: "TARGET_ENVIRONMENT_NAME"),
            string(name: 'PREFIX', defaultValue: '', description: 'Prefix for snapshot name (optional). Use representative name e.g pre-aurora-rollout, post-pg-upgrade'),   
            string(name: 'RENTENTION_IN_DAYS', defaultValue: '', description: 'Snapshot retention in days (default: 7)'),
            text(description: "Optional. Only set this for external AWS account environment e.g Dreamhaven Stage, Dreamhaven Prod etc", name: 'EXT_ACC_AWS_CREDS', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" )
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
                image: 268237257165.dkr.ecr.us-west-2.amazonaws.com/rollout-pipeline:1.0.1
                resources:
                  requests:
                    memory: 256Mi
                    cpu: 250m
                  limits:
                    cpu: 1000m
                    memory: 768Mi
        '''
    }
  }
  

    stages {


        stage('Skip stages') {
            when { expression { params.TARGET_ENVIRONMENT_NAME == 'blank'} }
            steps {
                echo "Target environment is blank, skipping the rest of the stages..."
            }

        }

        stage('Read Environment Information') {
            when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
            steps {
                script {
                    def environmentDetails = getEnvironmentDetails(params.TARGET_ENVIRONMENT_NAME)
                    env.TARGET_ENVIRONMENT_NAME = params.TARGET_ENVIRONMENT_NAME
                    env.AURORA_TYPE   = params.AURORA_TYPE 

                    def (customer, project, environment) = params.TARGET_ENVIRONMENT_NAME.split('-')

                    env.ENVIRONMENT = environment

                    env.CUSTOMER_NAME = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
                    echo '$environmentDetails' | jq -r '.customerName'
                    """).trim()

                    env.PROJECT = sh (label: 'Set CUSTOMER_NAME', returnStdout: true, script: """#!/bin/bash
                    echo '$environmentDetails' | jq -r '.project'
                    """).trim()

                    env.AWS_ACCOUNT = sh (label: 'Set AWS_ACCOUNT', returnStdout: true, script: """#!/bin/bash
                    echo '$environmentDetails' | jq -r '.account'
                    """).trim()

                    env.AWS_REGION = sh (label: 'Set AWS_REGION', returnStdout: true, script: """#!/bin/bash
                    echo '$environmentDetails' | jq -r '.region'
                    """).trim()
                }
            }
        }


        stage('Preparation') {
            when { expression { params.TARGET_ENVIRONMENT_NAME != 'blank' } }
            steps {

                // Configure Default Role
                sh 'echo "[default]" > aws.config'
                sh 'aws configure set web_identity_token_file $AWS_WEB_IDENTITY_TOKEN_FILE --profile default'
                sh 'aws configure set role_arn $AWS_ROLE_ARN --profile default'

                // Configure Automation Platform Role
                sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set role_arn arn:aws:iam::${env.AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform"
                sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation set source_profile default"

                // Configure Automation Platform Terraform Role
                sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set role_arn arn:aws:iam::${env.AWS_ACCOUNT}:role/${TARGET_ENVIRONMENT_NAME}-automation-platform-terraform"
                sh "aws configure --profile ${TARGET_ENVIRONMENT_NAME}-automation-terraform set source_profile ${TARGET_ENVIRONMENT_NAME}-automation"

                // Set AWS profile to target environment profile from now on.
                script {
                    env.AWS_PROFILE = "${TARGET_ENVIRONMENT_NAME}-automation-terraform"

                    def creds = params.EXT_ACC_AWS_CREDS.split('\n')
                    def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = creds.size() >= 3 ? [creds[0], creds[1], creds[2]] : [creds[0], creds[1], null]

                    if (awsAccessKeyId != "export AWS_ACCESS_KEY_ID=asd" ){
                        env.AWS_ACCESS_KEY_ID = awsAccessKeyId.replaceAll('"', '').split('=')[1]
                        env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey.replaceAll('"', '').split('=')[1]
                        if (awsSessionToken != null) env.AWS_SESSION_TOKEN = awsSessionToken.replaceAll('"', '').split('=')[1]
                        sh 'aws sts get-caller-identity --no-cli-pager'
                    }

                }
            }
        }

        stage('User Input: Choose Target') {
            steps {
                script {

                    echo "Fetching all DB instances (RDS and DocDB)..."

                    def dbJson = sh(
                        script: """aws rds describe-db-instances \
                            --query 'DBInstances[*].{id:DBInstanceIdentifier, engine:Engine, cluster:DBClusterIdentifier}' \
                            --output json""",
                        returnStdout: true
                    ).trim()

                    def dbList = readJSON text: dbJson

                    def dbMap = [:]
                    dbList.each { db ->
                        def id = db.id
                        def engine = db.engine
                        def cluster = db.cluster ?: ''
                        dbMap[id] = [engine: engine, cluster: cluster]
                    }

                    echo "Choose the target DB instances (RDS and DocDB) by clicking below"
                    def choices = dbMap.keySet() as List
                    def selected = input message: 'Select a DB instance to snapshot:', parameters: [
                        choice(name: 'DB_INSTANCE', choices: choices.join('\n'), description: 'Choose one')
                    ]

                    env.SELECTED_DB = selected
                    env.SELECTED_ENGINE = dbMap[selected].engine
                    env.SELECTED_CLUSTER = dbMap[selected].cluster
                    echo "Selected: ${selected}, Engine: ${env.SELECTED_ENGINE}, Cluster: ${env.SELECTED_CLUSTER}"
                    
                }
            }
        }


        stage('Create Snapshot') {
            steps {
                script {

                    def build_triggered_by = currentBuild.getBuildCauses()[0].userId
                    def timestamp = new Date().format('yyyyMMddHHmmss')
                    def prefix = params.PREFIX?.trim()
                    def retention = params.RETENTION_IN_DAYS?.trim() ?: '7'
                    def snapshotBase = prefix ? "${prefix}-${timestamp}" : "jenkins-snapshot-${timestamp}"
                    def selectedCluster = env.SELECTED_CLUSTER?.trim()
                    def isCluster = selectedCluster && selectedCluster != 'null'

                    def tagResourceId = isCluster ? selectedCluster : env.SELECTED_DB
                    def tagResourceType = isCluster ? 'cluster' : 'db'

                    echo "Fetching tags from ${tagResourceType} ${tagResourceId}"

                    def tagJson = sh(
                        script: "aws rds list-tags-for-resource --resource-name arn:aws:rds:${env.AWS_REGION}:${env.AWS_ACCOUNT}:${tagResourceType}:${tagResourceId} --output json",
                        returnStdout: true
                    ).trim()

                    def existingTags = readJSON text: tagJson
                    def tagList = existingTags.TagList ?: []

                    // Add custom tags
                    tagList << [Key: 'owner', Value: (build_triggered_by ?: 'unknown')]
                    tagList << [Key: 'jenkins', Value: 'true']
                    tagList << [Key: 'retention_in_days', Value: retention]
                    
                    def tagArgs = tagList.collect { "Key=${it.Key},Value='${it.Value}'" }.join(' ')

                    if (env.SELECTED_CLUSTER && env.SELECTED_CLUSTER != 'null') {
                        def clusterSnapshotId = "${snapshotBase}"
                        echo "Creating CLUSTER snapshot ${clusterSnapshotId} for cluster ${env.SELECTED_CLUSTER}"

                        sh """
                        aws rds create-db-cluster-snapshot \
                            --db-cluster-snapshot-identifier ${clusterSnapshotId} \
                            --db-cluster-identifier ${env.SELECTED_CLUSTER} \
                            --tags ${tagArgs} \
                            --region ${env.AWS_REGION}
                        """
                    } else {
                        def snapshotId = "${snapshotBase}"
                        echo "Creating INSTANCE snapshot ${snapshotId} for ${env.SELECTED_DB}"

                        sh """
                        aws rds create-db-snapshot \
                            --db-snapshot-identifier ${snapshotId} \
                            --db-instance-identifier ${env.SELECTED_DB} \
                            --tags ${tagArgs} \
                            --region ${env.AWS_REGION}
                        """
                    }
                }
            }
        }
    }
}

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
        envData.push("Error getting environment list data")
    } else {
        return envData
    }
}

void getEnvironmentDetails(envName) {
  def eksClusters = ""
  withCredentials([string(credentialsId: "db-inventory-api-key", variable: 'DB_INVENTORY_API_KEY')]) {
      eksClusters = sh (label: 'Download environment data', returnStdout: true, script: '''#!/bin/bash
      curl -s -XGET -H 'accept: application/json' -H "x-api-key: $DB_INVENTORY_API_KEY" 'https://dbinventory-api.dev.hosting.accelbyte.io/listResourcesByType?ResourceType=EKS'
      ''').trim()
  }

  def environmentDetail = sh (label: 'Get environment details', returnStdout: true, script: """#!/bin/bash
  echo '$eksClusters' | jq '.resources[] | select(.name == "$envName") | {name: .name, region: .region, account: .account} + {customerName: .details.Tags[] | select (.Key == "customer_name").Value} + {project: .details.Tags[] | select (.Key == "project").Value} + {environment: .details.Tags[] | select (.Key == "environment").Value}'
  """).trim()

  return environmentDetail
}
