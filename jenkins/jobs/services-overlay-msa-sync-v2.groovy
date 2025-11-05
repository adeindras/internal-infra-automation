import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
@Field List<String> serviceFound = []
@Field List<String> serviceNotFound = []

properties(
  [
    parameters([
      string(defaultValue: '', name: 'targetCCU'),
      string(defaultValue: '', name: 'identifier'),
      string(defaultValue: '', name: 'targetEnvironmentName'),
      string(defaultValue: '', name: 'msaData'),
      string(defaultValue: '', name: 'slackThread')
    ])
  ]
)

// constants
def targetCCU = params.targetCCU
def targetEnvironmentName = params.targetEnvironmentName
def identifier = params.identifier
def msaData = params.msaData
def branchName = "autorightsizing-${targetEnvironmentName}-CCU${targetCCU}-${identifier}"
def tempDir = "tmpdir${BUILD_NUMBER}"
def buildStopped = false
def slackThread = params.slackThread
def prHtmlLink
def hasChanges
def commitMessage
// def serviceNotFound = []
// def serviceFound = []
def branchIdentifier

def restoreHeaders(originalLines, updatedYamlText) {
    def headerLines = []
    for (line in originalLines) {
        if (line.trim().isEmpty() || line.trim().startsWith("#") || line.trim() == "---") {
            headerLines << line
        } else if (line.trim().contains(":")) {
            break
        }
    }
    return (headerLines + updatedYamlText.readLines()).join("\n")
}

def manipulateConfigmap(service) {
    def autorightsizingAnnotation = '# {"autorightsizingTool": true}'

    def envVars = [:]
    try {
        if (service.environment_variables != null) {
            envVars = service.environment_variables
        }
    } catch (MissingPropertyException ignored) {}

    def allConfigmapManifests = sh(
        returnStdout: true,
        script: "grep -ol '^kind: ConfigMap' *.*ml || true"
    ).trim().split('\n').findAll { it }

    allConfigmapManifests.each { fileName ->
        def originalLines = readFile(fileName).readLines()
        def manifest = readYaml file: fileName

        if (manifest.metadata.name != "${service.name}-variables") {
            echo "Skipping ${fileName}: name mismatch."
            return
        }

        echo "Modifying configmap manifest ${fileName}"

        def newLines = []
        def insideData = false

        originalLines.each { line ->
            if (line.trim() == "data:") {
                insideData = true
                newLines << line
                return
            }

            if (insideData) {
                if (line.startsWith("  ")) {
                    def match = line =~ /^\s{2}([^:]+):\s*(.*?)(\s+#\s*(\{.*\}))?$/
                    if (match) {
                        def key = match[0][1]
                        def comment = match[0][3]
                        if (comment?.contains(autorightsizingAnnotation)) {
                            return
                        }
                        newLines << line
                        return
                    }
                } else {
                    insideData = false
                    newLines << line
                }
            } else {
                newLines << line
            }
        }

        envVars.each { k, v ->
            def quotedValue = "\"${v}\""
            def annotatedLine = "  ${k}: ${quotedValue} ${autorightsizingAnnotation}"
            newLines << annotatedLine
        }

        writeFile file: fileName, text: newLines.join("\n")
    }
}

def manipulateDeployment(service) {
    allDeploymentManifests = sh(
        returnStdout: true,
        script: """
            grep -ol "^kind: Deployment" *.*ml | cat
        """).trim()
            .split('\n')
            .findAll { it.trim() }
    
    allDeploymentManifests.each {
        def originalLines = readFile(it).readLines()
        def manifest = readYaml file: it
        if (manifest.metadata.name == service.name) {
            println("Modifying deployment manifest ${service.name} ${it}")
            try {
                if (service.cpu_limit == 'null') service.cpu_limit = null
                manifest.spec.template.spec.containers[0].resources.requests.memory = service.memory_req
                manifest.spec.template.spec.containers[0].resources.limits.memory = service.memory_limit
                manifest.spec.template.spec.containers[0].resources.requests.cpu = service.cpu_req
                manifest.spec.template.spec.containers[0].resources.limits.cpu = service.cpu_limit

                def updatedYaml = writeYaml returnText: true, data: manifest
                def finalYaml = restoreHeaders(originalLines, updatedYaml)
                writeFile file: it, text: finalYaml
            } catch(Exception ex) {
                println("error editing deployment manifest ${service.name} ${it}")
            }
        }
    }
}

def manipulateHpa(service) {
    allHpaManifests = sh(
        returnStdout: true,
        script: """
            grep -ol "^kind: HorizontalPodAutoscaler" *.*ml | cat
        """).trim()
            .split('\n')
            .findAll { it.trim() }
    
    allHpaManifests.each { it ->
        def originalLines = readFile(it).readLines()
        def manifest = readYaml file: it
        if (manifest.metadata.name == service.name) {
            println("Modifying hpa manifest ${service.name} ${it}")
            try {
                manifest.spec.minReplicas = service.hpa_min.toInteger()
                manifest.spec.maxReplicas = service.hpa_max.toInteger()
                manifest.spec.metrics.each { mt ->
                    if (mt.resource.name == "cpu") mt.resource.target.averageUtilization = service.hpa_cpu.toInteger()
                    if (mt.resource.name == "memory") mt.resource.target.averageUtilization = service.hpa_memory.toInteger()
                }

                def updatedYaml = writeYaml returnText: true, data: manifest
                def finalYaml = restoreHeaders(originalLines, updatedYaml)
                writeFile file: it, text: finalYaml
            } catch(Exception ex) {
                println("error editing hpa manifest ${service.name} ${it}")
            }
        }
    }
}

def manipulateServiceMeshAnnotation(service) {
    allInfraDeploymentManifest = sh(
        returnStdout: true,
        script: """
            if [[ -d infrastructure ]]; then
                grep -ol "^kind: Deployment" infrastructure/*.*ml | cat
            fi
        """).trim()
            .split('\n')
            .findAll { it.trim() }
    
    allInfraDeploymentManifest.each { it ->
        def originalLines = readFile(it).readLines()
        def manifest = readYaml file: it
        if (manifest.metadata.name == service.name) {
            println("Modifying service mesh annotation ${service.name} ${it}")
            try {
                manifest.spec.template.metadata.annotations."config.linkerd.io/proxy-cpu-request" = service.mesh_cpu_req
                if (service.mesh_cpu_limit != 'null') {
                    manifest.spec.template.metadata.annotations."config.linkerd.io/proxy-cpu-limit" = service.mesh_cpu_limit
                }
                manifest.spec.template.metadata.annotations."config.linkerd.io/proxy-memory-request" = service.mesh_memory_req
                manifest.spec.template.metadata.annotations."config.linkerd.io/proxy-memory-limit" = service.mesh_memory_limit

                def updatedYaml = writeYaml returnText: true, data: manifest
                def finalYaml = restoreHeaders(originalLines, updatedYaml)
                writeFile file: it, text: finalYaml
            } catch(Exception ex) {
                println("error editing service mesh annotation ${service.name} ${it}")
            }
        }
    }
}

def manipulateConfigmapInfo() {
    allConfigmapManifests = sh(
        returnStdout: true,
        script: """
            grep -ol "^kind: ConfigMap" *.*ml | cat
        """).trim()
            .split('\n')
            .findAll { it.trim() }

    if (allConfigmapManifests.isEmpty()) {
        println("No ConfigMap manifests found.")
        return
    }

    allConfigmapManifests.each { manifestFile ->
        def manifest = readYaml file: manifestFile

        if (manifest?.data?.CCU) {
            println("Updating CCU in ${manifestFile} to ${targetCCU}")
            try {
                manifest.data.CCU = targetCCU
                writeYaml file: manifestFile, data: manifest, overwrite: true
                println("Successfully updated ${manifestFile}")
            } catch (Exception ex) {
                println("Error updating ${manifestFile}: ${ex.message}")
            }
        } else {
            println("Skipping ${manifestFile}: No 'CCU' field found.")
        }
    }
}

def manipulateServiceManifest(service) {
    def (customer, project, environment) = targetEnvironmentName.split('-')
    def serviceDirectory = "${customer}/${project}/${environment}/services-overlay/${service.directory}"
    def abInfraManagerDirectory = "${customer}/${project}/${environment}/services-overlay/ab-infra-manager"

    if (fileExists(abInfraManagerDirectory)) {
        dir(abInfraManagerDirectory) {
            manipulateConfigmapInfo()
        }
    } else {
        println("Skipping ConfigMap update: ab-infra-manager directory not found.")
    }

    if (fileExists(serviceDirectory)) {
        serviceFound.push(service.name)
        dir(serviceDirectory) {
            manipulateConfigmap(service)
            manipulateDeployment(service)
            manipulateHpa(service)
            manipulateServiceMeshAnnotation(service)
        }
    } else {
        serviceNotFound.push(service.name)
    }
}

def manipulateFluxCDManifest(service) {
    echo "serviceName: ${service.name}"

    def manifestFile = "${service.name}.yaml"

    if (service.cpu_limit == 'null') service.cpu_limit = null
    
    def hpaMin = service.hpa_min.toInteger()
    def hpaMax = service.hpa_max.toInteger()
    def hpaCpuThreshold = service.hpa_cpu.toInteger()
    def hpaMemoryThreshold = service.hpa_memory.toInteger()

    sh """
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.MEMORY_REQUEST = "${service.memory_req}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.MEMORY_LIMIT = "${service.memory_limit}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.CPU_REQUEST = "${service.cpu_req}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.CPU_LIMIT = "${service.cpu_limit}"' -i ${manifestFile}

        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.MIN_REPLICAS = "${hpaMin}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.MAX_REPLICAS = "${hpaMax}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.HPA_CPU_THRESHOLD = "${hpaCpuThreshold}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.HPA_MEMORY_THRESHOLD = "${hpaMemoryThreshold}"' -i ${manifestFile}

        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.LINKERD_CPU_REQUEST = "${service.mesh_cpu_req}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.LINKERD_MEMORY_REQUEST = "${service.mesh_memory_req}"' -i ${manifestFile}
        yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.LINKERD_MEMORY_LIMT = "${service.mesh_memory_limit}"' -i ${manifestFile}
    """

    if (service.mesh_cpu_limit != 'null') {
        sh """
            yq eval 'select(.metadata.name == "${service.name}-flux-variables").data.LINKERD_CPU_LIMIT = "${service.mesh_cpu_limit}"' -i ${manifestFile}
        """
    }
}

def manipulateSpecializedFluxCDManifest(service) {
    echo "serviceName: ${service.name}"

    // do nothing for now

    // TODO: handle following services
    // - analytics-configuration-service.yaml
    //   - LINKERD_ANALYTICS_CONFIGURATION_CPU_REQUEST
    //   - LINKERD_ANALYTICS_CONFIGURATION_MEMORY_LIMIT
    //   - LINKERD_ANALYTICS_CONFIGURATION_MEMORY_REQUEST
    //   - ANALYTICS_CONFIGURATION_MONITORING_MEMORY_LIMIT
    //   - ANALYTICS_CONFIGURATION_MONITORING_CPU_REQUEST
    //   - ANALYTICS_CONFIGURATION_MONITORING_MEMORY_REQUEST
    //   - no MIN_REPLICAS
    //   - no MAX_REPLICAS
    //   - no HPA_CPU_THRESHOLD
    //   - no HPA_MEMORY_THRESHOLD
    // - analytics-kafka-connect.yaml
    //   - KAFKA_CONNECT_API_LINKERD_CPU_REQUEST
    //   - KAFKA_CONNECT_API_LINKERD_MEMORY_LIMIT
    //   - KAFKA_CONNECT_API_LINKERD_MEMORY_REQUEST
    //   - KAFKA_CONNECT_UI_LINKERD_CPU_REQUEST
    //   - KAFKA_CONNECT_UI_LINKERD_MEMORY_LIMT
    //   - KAFKA_CONNECT_UI_LINKERD_MEMORY_REQUEST
    //   - CPU_REQUEST_API
    //   - MEMORY_REQUEST_API
    //   - MEMORY_LIMIT_API
    //   - CPU_REQUEST_UI
    //   - CPU_LIMIT_UI
    //   - MEMORY_REQUEST_UI
    //   - MEMORY_LIMIT_UI
    //   - CPU_REQUEST_MONITORING
    //   - CPU_LIMIT_MONITORING
    //   - MEMORY_REQUEST_MONITORING
    //   - MEMORY_LIMIT_MONITORING
}

def manipulateFluxCD(service) {
    def (customer, project, environment) = targetEnvironmentName.split('-')
    def serviceDirectory = "${customer}/${project}/${environment}/services"
    def serviceManifest = "${customer}/${project}/${environment}/services/${service.directory}.yaml"

    echo "serviceDirectory: ${serviceDirectory}"
    echo "serviceManifest: ${serviceManifest}"

    if (fileExists(serviceManifest)) {
        serviceFound.push(service.name)
        dir(serviceDirectory) {
            if (service.directory == "analytics-configuration-service" || service.directory == "analytics-kafka-connect") {
                manipulateSpecializedFluxCDManifest(service)
            } else {
                manipulateFluxCDManifest(service)  
            }
        }
    } else {
        serviceNotFound.push(service.name)
    }
}

node('infra-sizing') {
    container('tool') {
        stage('Check Params') {
            if (WORKSPACE.contains("DEVELOPMENT")) {
                branchIdentifier = "DEVELOPMENT"
            }
            if (targetCCU == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (targetEnvironmentName == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (msaData == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            if (identifier == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('Aborting the build')
            }
            branchName = "autorightsizing-${targetEnvironmentName}-CCU${targetCCU}-${identifier}"
            commitMessage = "feat: ${branchIdentifier}${targetEnvironmentName} autorightsizing to ${targetCCU} CCU"
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

        stage('Processing Data') {
            dir(tempDir) {
                dir('deployments') {

                    def (customer, project, environment) = targetEnvironmentName.split('-')
                    def serviceOverlayDirectory = "${customer}/${project}/${environment}/services-overlay"
                    
                    if (fileExists(serviceOverlayDirectory)) {
                        def jsonSlurper = new JsonSlurper()
                        def services = jsonSlurper.parseText(msaData)
                        def stepsForParallel = [:]
                        services.services.each {
                            switch (it.name) {
                                case "analytics-airflow-scheduler":
                                    it.directory = "analytics-airflow"
                                    break;
                                case "analytics-airflow-web":
                                    it.directory = "analytics-airflow"
                                    break;
                                case "analytics-game-telemetry-api":
                                    it.directory = "analytics-game-telemetry/${it.name}"
                                    break;
                                case "analytics-game-telemetry-monitoring":
                                    it.directory = "analytics-game-telemetry/${it.name}"
                                    break;
                                case "analytics-game-telemetry-worker":
                                    it.directory = "analytics-game-telemetry/${it.name}"
                                    break;
                                case "justice-dsm-controller-service":
                                    it.directory = "justice-dedicated-server-manager-controller-service"
                                    break;
                                case "platform-engineering-status-page-fe":
                                    it.directory = "platform-engineering-status-page-service-frontend"
                                    break;
                                default:
                                    it.directory = it.name
                                    break;
                            }
                            stepsForParallel[it.name] = { ->           
                                manipulateServiceManifest(it)
                            }
                        }
                        parallel stepsForParallel
                    } else {
                        // execute fluxcd code here
                        def jsonSlurper = new JsonSlurper()
                        def services = jsonSlurper.parseText(msaData)
                        def stepsForParallel = [:]
                        services.services.each {
                            switch (it.name) {
                                case "analytics-airflow-scheduler":
                                    it.directory = "analytics-airflow.yaml"
                                    break;
                                case "analytics-airflow-web":
                                    it.directory = "analytics-airflow.yaml"
                                    break;
                                case "analytics-game-telemetry-api":
                                    it.directory = "analytics-game-telemetry.yaml"
                                    break;
                                case "analytics-game-telemetry-monitoring":
                                    it.directory = "analytics-game-telemetry.yaml"
                                    break;
                                case "analytics-game-telemetry-worker":
                                    it.directory = "analytics-game-telemetry.yaml"
                                    break;
                                case "justice-dsm-controller-service":
                                    it.directory = "justice-dedicated-server-manager-controller-service.yaml"
                                    break;
                                case "platform-engineering-status-page-fe":
                                    it.directory = "platform-engineering-status-page-fe.yaml"
                                    break;
                                default:
                                    it.directory = it.name
                                    break;
                            }
                            stepsForParallel[it.name] = { ->           
                                manipulateFluxCD(it)
                            }
                        }
                        parallel stepsForParallel
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
                                git config --global user.email "build@accelbyte.net"
                                git config --global user.name "Build AccelByte"
                                git checkout -b ${branchName}
                                git add .
                                git commit -m "${commitMessage}"
                                GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" git push origin ${branchName}
                            """
                        }
                        
                    }
                }
            }
        }

        stage("Report") {
            println("Services not found::")
            println(serviceNotFound)
            println("Services found::")
            println(serviceFound)
        }


        stage('Create PR') {
            prSummary="""
:: Service found: \n $serviceFound \n \n
:: Service not found: \n $serviceNotFound \n \n
            """
            withCredentials([string(credentialsId: 'BitbucketAppKeyUserPassB64', variable: 'BitbucketAppKeyUserPassB64')]) {
                // POST
                def post = new URL('https://bitbucket.org/api/2.0/repositories/accelbyte/deployments/pullrequests').openConnection()
                def postData =  [
                    title: "feat: Auto Rightsizing ${targetEnvironmentName} to ${targetCCU} CCU",
                    source: [
                        branch: [
                            name: "${branchName}"
                        ]
                    ],
                    reviewers:[],
                    destination: [
                        branch: [
                            name: 'master'
                        ]
                    ],
                    summary: [
                        raw: "${prSummary}"
                    ],
                    close_source_branch: true
                ]
                def jsonPayload = JsonOutput.toJson(postData)
                post.setRequestMethod('POST')
                post.setDoOutput(true)
                post.setRequestProperty('Content-Type', 'application/json')
                post.setRequestProperty('Authorization', "Basic ${BitbucketAppKeyUserPassB64}")
                post.getOutputStream().write(jsonPayload.getBytes('UTF-8'))
                def postRC = post.getResponseCode()
                if (postRC.equals(200) || postRC.equals(201)) {
                    def jsonSlurper = new JsonSlurper()
                    def reply = post.getInputStream().getText()
                    def replyMap = jsonSlurper.parseText(reply)
                    prHtmlLink = replyMap.links.html.href
                    println(prHtmlLink)
                }
            }
        }

        stage('Sending Slack Notification') {
            def elapsedTime = currentBuild.durationString.replaceAll(' and counting', '')
            withCredentials([string(credentialsId: 'ab-deploy-automation-slackbot-token', variable: 'slackToken')]) {
                // POST
                def nServiceFound = serviceFound.size()
                def nServiceNotFound = serviceNotFound.size()
                prSummary="""
:: Total processed service: \n $nServiceFound \n \n
:: Total not processed service: \n $nServiceNotFound \n \n
:: Not processed services (PLEASE CHECK IT MANUALLY): \n $serviceNotFound \n \n
                """
                def post = new URL('https://slack.com/api/chat.postMessage').openConnection()
                def postData =  [
                    channel: 'C079A11910R',
                    blocks: [
                        [
                            type: 'section',
                            text: [
                                type: 'mrkdwn',
                                text: ':k8s: Sync service overlay with MSA data done :k8s:\nNOTE!!!\nThese services are not updated, please do it manually:\nopentelemetry-collector\njustice-playerportal-website\n'
                            ]
                        ],
                        [
                            type: 'section',
                            fields: [
                                [
                                    type: 'mrkdwn',
                                    text: "*Jenkins:*\n<${BUILD_URL}/console|Go to Jenkins!>"
                                ],
                                [
                                    type: 'mrkdwn',
                                    text: "*PR:*\n<${prHtmlLink}/console|Check PR!>"
                                ],
                                [
                                    type: 'mrkdwn',
                                    text: "*Summary:*\n${prSummary}"
                                ],
                                [
                                    type: 'mrkdwn',
                                    text: "*Execution Time:*\n${elapsedTime}"
                                ],
                            ]
                        ]
                    ],
                        thread_ts: "${slackThread}"
                ]
                def jsonPayload = JsonOutput.toJson(postData)
                post.setRequestMethod('POST')
                post.setDoOutput(true)
                post.setRequestProperty('Content-Type', 'application/json')
                post.setRequestProperty('Authorization', "Bearer ${slackToken}")
                post.getOutputStream().write(jsonPayload.getBytes('UTF-8'))
                def postRC = post.getResponseCode()
                if (postRC.equals(200) || postRC.equals(201)) {
                    println(post.getInputStream().getText())
                }
            }
        }
    }
}