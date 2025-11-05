import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def identifier = new Date().getTime().toString()
def tempDir = "tmpdir${BUILD_NUMBER}"
def branchName = "master"
def hasChanges

def scalingBlueprintGetData(endpoint, apiKey) {
    def uri = "http://internal-scaling-blueprint.devportal/scalingblueprint" + endpoint
    def get = new URL(uri).openConnection();
    get.setRequestProperty("Authorization", "Bearer " + apiKey);
    def getRC = get.getResponseCode();
    println(getRC);
    if (getRC.equals(200)) {
        def reply = get.getInputStream().getText()
        return reply
    }
    return null
}

def getEnvironmentList() {
    envs = []
    withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
        r = scalingBlueprintGetData("/admin/v1/clusters", ScalingBlueprintAPIKey)
        if (r != null) {
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

def manipulateDeploymentKustomization(clusterName) {
    deploymentManifests = sh(
        returnStdout: true,
        script: """
            grep -ol "^kind: Kustomization" *.*ml | cat
        """).trim()
        .split('\n')
        .findAll { it.trim() }

    deploymentManifests.each {
        def manifest = readYaml file: it
        if (!manifest.resources.contains("./${clusterName}.yaml")) {
            println("Modifying deployment manifest ${clusterName} in file ${it}")
            try {
                sh """#!/bin/bash
                    yq -i '.resources += ["./${clusterName}.yaml"]' kustomization.yaml
                """
            } catch (Exception ex) {
                println("error editing deployment manifest ${clusterName} in file ${it}")
            }
        }
    }
}

def deleteUnusedServiceAccounts(saList, envList, tempDir) {
    def saClusterNames = saList.collect { saName ->
        saName.startsWith('jenkins-agent-') ? saName.replaceFirst('jenkins-agent-', '') : null
    }.findAll { it }

    def unusedClusters = saClusterNames.findAll { !(envList.contains(it)) }

    if (unusedClusters.isEmpty()) {
        echo "No unused ServiceAccounts to delete. saList and envList are in sync."
        echo "saList: ${saClusterNames}"
        echo "envList: ${envList}"
        return
    }

    unusedClusters.each { clusterName ->
        def fullSAName = "jenkins-agent-${clusterName}"
        echo "Deleting unused ServiceAccount: ${fullSAName}"
        try {
            sh """
                kubectl delete serviceaccount ${fullSAName} -n jenkins
            """
            println "Deleted ServiceAccount: ${fullSAName}"
        } catch (Exception ex) {
            echo "Failed to delete ServiceAccount: ${fullSAName}, error: ${ex.message}"
        }
    }
}

node('deploy-agent') {
    container('tool') {
        dir(tempDir) {
            envList = []
            stage('Get All Environment List from API') {
                envList = getEnvironmentList()
                echo "Environment List: ${envList.toString()}"
            }

            stage('Checkout deployments repo') {
                dir(tempDir) {
                    sshagent(['bitbucket-repo-read-only']) {
                        sh '''
                            GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
                            git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/deployments.git" || true
                            chown -R 1000:1000 deployments
                        '''
                    }
                }
            }

            stage('Get cluster information') {
                dir(tempDir) {
                    dir('deployments/accelbyte/devportal/prod') {
                        def fileContent = readFile('cluster-information.env').trim()
                        def lines = fileContent.tokenize('\n')
                        lines.each { line ->
                            def (key, value) = line.tokenize('=')
                            env."${key}" = "${value}"
                            echo "${key} = ${value}"
                        }
                    }
                }
            }

            stage("Generate Kubeconfig") {
                sh """#!/bin/bash
                    set -e
                    set -o pipefail
                    envsubst < ~/.aws/config.template > ~/.aws/config
                    # aws sts get-caller-identity
                    aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_REGION}
                """
            }

            stage('Get Deployed Environment List from Kubectl') {
                sh """
                    kubectl get serviceaccounts -n jenkins
                    mkdir -p ${WORKSPACE}/${tempDir}
                    kubectl get serviceaccounts -n jenkins -o json \
                      | jq -r '.items[] | select(.metadata.name | startswith("jenkins-agent-")) | .metadata.name' \
                      > ${WORKSPACE}/${tempDir}/jenkins-serviceaccounts.txt
                """
            }

            stage('Comparing and Processing Environment Lists') {

                // saList is from the cluster (deployed), envList is from scalingblueprint API (wanted)
                dir(tempDir) {
                    def saList = readFile("${WORKSPACE}/${tempDir}/jenkins-serviceaccounts.txt").trim().split("\\s+")

                    dir('deployments') {
                        // Check & Delete
                        deleteUnusedServiceAccounts(saList, envList, tempDir)

                        // Check & Create
                        envList.each { val ->
                            echo "Processing adding environment: ${val}"

                            def uri = "/admin/v1/clusters/${val}"
                            def clusterName = ""
                            def awsAccountId = ""
                            def awsRegion = ""

                            withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
                                def r = scalingBlueprintGetData(uri, ScalingBlueprintAPIKey)
                                if (r) {
                                    def jsonSlurper = new groovy.json.JsonSlurper()
                                    def reply = jsonSlurper.parseText(r)
                                    clusterName = reply.clusterName
                                    awsAccountId = reply.awsAccountID
                                    awsRegion = reply.awsRegion
                                }
                            }

                            def saFound = false

                            if (clusterName && awsAccountId && awsRegion) {
                                echo clusterName
                                echo awsAccountId
                                echo awsRegion

                                saFound = saList.contains("jenkins-agent-${clusterName}")

                                if (!saFound) {
                                    echo "ServiceAccount jenkins-agent-${clusterName} not found, creating SA manifest..."
                                    dir('accelbyte/devportal/prod/services-overlay/jenkins-automation-platform-sa') {

                                        def saFile = "${clusterName}.yaml"
                                        // Create filepath in kustomization.yaml
                                        manipulateDeploymentKustomization(clusterName)
                                        // Create the template with cluster name
                                        writeFile file: saFile, text: """\
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins-agent-${clusterName}
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::${awsAccountId}:role/${clusterName}-automation-platform-terraform
                            """
                                    }
                                } else {
                                    echo "ServiceAccount jenkins-agent-${clusterName} already exists."
                                }
                            } else {
                                echo "Failed to get cluster data for ${val}"
                            }
                        }
                    }
                }
            }

            stage("Commit & Push") {
                dir(tempDir) {
                    dir('deployments') {
                        sh "git config --global --add safe.directory '*'"
                        sh "git status -s"
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
                                    commitAndPush() {
                                      git checkout ${branchName}
                                      git config --global user.email "build@accelbyte.net"
                                      git config --global user.name "Build AccelByte Autorightsizing"
                                      git add .
                                      git commit -m "chore: Service Accounts sync (daily) for Autorightsizing tool ${identifier}. Committed by Jenkins"
                                      GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" git push origin ${branchName}
                                    }
        
                                    commitAndPush
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
