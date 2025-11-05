import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def identifier = new Date().getTime().toString()
def tempDir = "tmpdir${BUILD_NUMBER}"

// todo: add posibility to check on one env only

def scalingBlueprintData(endpoint, apiKey, method, body = null) {
    def uri = "http://internal-scaling-blueprint.devportal/scalingblueprint" + endpoint
    def curlBody = ""
    if (body) {
        def escapedBody = body.replace('"', '\\"')
        curlBody = "-d \"${escapedBody}\""
    }

    def command = """curl -s -w "\\n%{http_code}" -X ${method} \
        -H "Authorization: Bearer ${apiKey}" \
        -H "Content-Type: application/json" \
        ${curlBody} \
        "${uri}" """

    def result
    try {
        result = sh(script: command, returnStdout: true).trim()
    } catch (err) {
        // curl failed (e.g. DNS error, network timeout), skip
        return null
    }

    def lines = result.readLines()
    if (!lines || lines.size() < 1) {
        return null
    }

    def statusCode = lines[-1]
    def responseBody = lines.size() > 1 ? lines[0..<(lines.size() - 1)].join("\n").trim() : ""

    if (statusCode == '200') {
        return responseBody
    } else if (statusCode == '204') {
        return statusCode
    } else {
        return null
    }
}

def getEnvironmentList() {
    def envs = []
    withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
        def r = scalingBlueprintData("/admin/v1/clusters", ScalingBlueprintAPIKey, "GET")
        if (r != null) {
            def jsonSlurper = new JsonSlurper()
            def replyMap = jsonSlurper.parseText(r)
            envs = replyMap.clusterNames
        }
    }

    if (!envs || envs.isEmpty()) {
        envs.push("Error getting env list data")
    }

    return envs
}

def patchClusterData(clusterName, ccu) {
    withCredentials([string(credentialsId: "ScalingBlueprintAPIKey", variable: 'ScalingBlueprintAPIKey')]) {
        def payload = [ccu: ccu]
        def body = JsonOutput.toJson(payload)
        def r = scalingBlueprintData("/admin/v1/clusters/${clusterName}", ScalingBlueprintAPIKey, "PATCH", body)
        if (r == '204') {
            echo "Updated cluster ${clusterName} data with ccu ${ccu}"
        }
    }
}

def readConfigmapCCU() {
    def allConfigmapManifests = sh(
        returnStdout: true,
        script: """
            grep -ol "^kind: ConfigMap" *.*ml | cat
        """).trim()
            .split('\n')
            .findAll { it.trim() }

    if (allConfigmapManifests.isEmpty()) {
        println("No ConfigMap manifests found.")

        return ""
    }

    for (manifestFile in allConfigmapManifests) {
        def manifest = readYaml file: manifestFile

        if (manifest?.data?.CCU) {
            return manifest?.data?.CCU
        } else {
            println("Skipping ${manifestFile}: No 'CCU' field found.")
        }
    }

    return ""
}

node('deploy-agent') {
    container('tool') {
        dir(tempDir) {
            def envList = []
            stage('Get All Environment List from API') {
                envList = getEnvironmentList()
                echo "Environment List: ${envList.toString()}"
            }

            stage('Checkout deployments repo') {
                sshagent(['bitbucket-repo-read-only']) {
                    sh '''
                        GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
                        git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/deployments.git" || true
                        chown -R 1000:1000 deployments
                    '''
                }
            }

            stage('Processing...') {
                def branch = envList.collectEntries { val ->
                    ["${val}": {
                        stage("Patching CCU for ${val}") {
                            def (customer, project, environment) = val.split('-')
                            def abInfraManagerDirectory = "${customer}/${project}/${environment}/services-overlay/ab-infra-manager"

                            dir('deployments') {
                                if (fileExists(abInfraManagerDirectory)) {
                                    dir(abInfraManagerDirectory) {
                                        def ccu = readConfigmapCCU()
                                        if (ccu && ccu ==~ /^\d+$/) {
                                            echo "Directory found. Updating CCU ${ccu} for ${val} in ${abInfraManagerDirectory}"

                                            patchClusterData(val, ccu)
                                        } else {
                                            echo "Directory found. Skipping ccu update: Invalid CCU '${ccu}' for ${val}"
                                        }
                                    }
                                } else {
                                    println("Directory not found. Skipping CCU update for ${abInfraManagerDirectory}")
                                }
                            }
                        }
                    }]
                }

                parallel branch
            }
        }
    }
}
