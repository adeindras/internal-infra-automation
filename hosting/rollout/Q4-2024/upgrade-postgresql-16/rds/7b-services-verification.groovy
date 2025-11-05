import groovy.json.JsonOutput
import groovy.json.JsonSlurper


envList = getEnvironmentList()

properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(description: "String to search in k8s", name: 'searchStrings', defaultValue: "postgresql12.postgresql" ),			
			text(description: "AWS Credentials. Generate from SSO when possible.", name: 'awsCreds', defaultValue: "export AWS_ACCESS_KEY_ID=asd\nexport AWS_SECRET_ACCESSS_KEY=asd" )			

        ])
    ]
)

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
        envData.push("Error getting env list data")
    } else {
        return envData
    }
}

String targetEnvironmentName = params.targetEnvironmentName

String envDirectory
String awsAccountId
String awsRegion

String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String BB_BRANCH_NAME

FOUND_SERVICES = []

currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-rds-postgresql-upgrade"

node('hosting-agent') {
	container('tool') {
		dir(tempDir){

			stage('Fetch required manifests and scripts') {
				dir('iac') {
                checkout scmGit(
                    branches: [[name: '*/master']],
                    extensions: [ cloneOption(shallow: true) ],
                    userRemoteConfigs: [[credentialsId:  'bitbucket-repo-read-only',
                        url: 'git@bitbucket.org:accelbyte/iac.git']])
				}
			}
			
			stage('Set aws credentials'){
				def (customer, project, environment) = targetEnvironmentName.split('-')
				dir('iac') {
					envDirectory = sh(returnStdout: true, script: """
						clusterDir=\$(find live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep ${environment} | grep -v terragrunt-cache | head -n 1)
						dirname \${clusterDir}
					"""
					).trim()
					awsAccountId = sh(returnStdout: true, script: """
						echo ${envDirectory} | egrep -o '[[:digit:]]{12}'
					"""
					).trim()
					awsRegion = sh(returnStdout: true, script: """
						basename \$(dirname ${envDirectory})
					"""
					).trim()

					manifestClusterDirectory = sh(returnStdout: true, script: """
						find manifests -path "*/${customer}/${project}/${awsRegion}/${environment}"
					"""
					).trim()

                    deploymentClusterDirectory = sh(returnStdout: true, script: """
						echo "${customer}/${project}/${environment}"
					"""
					).trim()

					sh """
						rm -rf ~/.aws/config || true
					"""

					// def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = params.awsCreds.split('\n')
                    def creds = params.awsCreds.split('\n')
                    def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = creds.size() >= 3 ? [creds[0], creds[1], creds[2]] : [creds[0], creds[1], null]
					env.AWS_ACCESS_KEY_ID = awsAccessKeyId.replaceAll('"', '').split('=')[1]
					env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey.replaceAll('"', '').split('=')[1]
                    if (awsSessionToken != null) env.AWS_SESSION_TOKEN = awsSessionToken.replaceAll('"', '').split('=')[1]
					env.AWS_DEFAULT_REGION = awsRegion
					env.AWS_REGION = awsRegion
					sh 'aws sts get-caller-identity --no-cli-pager'

				}
			}

			stage('Generate Kubeconfig') {
				sh """#!/bin/bash
					set -e
					set -o pipefail
					envsubst < ~/.aws/config.template > ~/.aws/config
					aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}
				"""
			}

			stage('Prepare common variable') {
				ssmPath = sh(returnStdout: true, script: """
					kubectl -n justice get cm cluster-variables -oyaml | yq .data.SSM_PATH
				""").trim()
			}
         
            stage('Gather Services using Postgres') {

                // Get all namespaces except excluded ones
                def excludeNamespaces = "kube-system,kube-public,kube-node-lease,default,monitoring,otelcollector,crossplane-system,karpenter,linkerd,linkerd-jaeger,linkerd-viz,emissary,emissary-system,logging,logstash,elasticearch"
                excludeNamespaces = excludeNamespaces.split(',')
                def namespaces = sh(
                    script: "kubectl get namespaces -o jsonpath='{.items[*].metadata.name}'",
                    returnStdout: true
                ).trim().split().findAll { !excludeNamespaces.contains(it) }
                
                // Process each namespace
                namespaces.each { namespace ->
                    def namespaceInfo = [
                        namespace: namespace,
                        resources: []
                    ]
                    
                    def configmapNSManifestPath = "${tempDir}/${namespace}/cm.json"
                    def secretNSManifestPath = "${tempDir}/${namespace}/secret.json"

                    sh "mkdir -p ${tempDir}/${namespace}"
                    sh "kubectl get configmap -n ${namespace} -o json > ${configmapNSManifestPath}"
                    sh "kubectl get secret -n ${namespace} -o json > ${secretNSManifestPath}"


                    // Check workload resources
                    def workloadTypes = [
                        'deployment': 'Deployment',
                        'statefulset': 'StatefulSet',
                        'daemonset': 'DaemonSet'
                    ]
                    
                    workloadTypes.each { type, label ->
                        def resources = sh(
                            script: "kubectl get ${type} -n ${namespace} -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || true",
                            returnStdout: true
                        ).trim()
                        
                        if (resources) {
                            resources.split().each { resourceName ->
                                checkResource(namespace, type, resourceName, label, configmapNSManifestPath, secretNSManifestPath, tempDir)
                            }
                        }
                    }
                    
                    def pods = sh(
                        script: """
                            kubectl get pods -n ${namespace} -o json | \
                            jq -r '.items[] | select(.metadata.ownerReferences | length == 0) | .metadata.name'
                        """,
                        returnStdout: true
                    ).trim()
                    
                    if (pods) {
                        pods.split('\n').each { podName ->
                            checkResource(namespace, 'pod', podName, 'Standalone Pod')
                        }
                    }
                }
            }


            stage('Generate Reports') {
            
                writeJSON file: "${tempDir}/services-postgres.json", json: FOUND_SERVICES
                writeYaml file: "${tempDir}/services-postgres.yaml", data: FOUND_SERVICES
                generateHtmlReport("${tempDir}/services-postgres.html")
                generateTextSummary("${tempDir}/services-postgres.txt")

                archiveArtifacts artifacts: "${tempDir}/*.html", fingerprint: true
                
                publishHTML (target : [allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: "${tempDir}",
                reportFiles: 'services-postgres.html',
                reportName: 'services-postgresql',
                reportTitles: 'List of Services using Postgresql/RDS'])
            
            }

            stage('Verify Services Env') {
            
                def postgresUsage = readJSON file: "${tempDir}/services-postgres.json"
                postgresUsage.each { service ->
                    // Process each service
                    echo "${service}"
                }
            
            }

        }
    }
}

def checkResource(namespace, resourceType, resourceName, resourceLabel, configmapManifestPath, secretManifestPath, tempDir) {
    def evidence = []
    def searchStrings = params.searchStrings.split(',')
    def isUsingPostgres = false
    
    def resourceManifestPath = "${tempDir}/${namespace}/${resourceType}-${resourceName}.json"
    sh "kubectl get ${resourceType} ${resourceName} -n ${namespace} -o json > ${resourceManifestPath}"

    // Check ConfigMaps
    def configMaps = sh(
        script: """
            cat ${resourceManifestPath} | jq -r '.spec.template.spec.containers[].envFrom[]?.configMapRef.name | select(. !=null)' || \
            cat ${resourceManifestPath} | jq -r '.spec.containers[].envFrom[]?.configMapRef.name | select(. !=null)' 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()
    
    if (configMaps) {
        configMaps.split().each { configMap ->
            def configMapContent = sh(
                script: "cat ${configmapManifestPath} | jq '.items[] | select(.metadata.name == \"${configMap}\")' 2>/dev/null || true",
                returnStdout: true
            )
            if (configMapContent =~ /(?i)postgres|pg_/) {
                evidence << [
                    type: 'ConfigMap',
                    name: configMap,
                    details: 'Contains PostgreSQL-related configuration'
                ]
                isUsingPostgres = true
            }
        }
    }
    // Check Secrets (similar to ConfigMaps)
    def secrets = sh(
        script: """
            cat ${resourceManifestPath} | jq '.spec.template.spec.containers[].envFrom[]?.secretRef.name | select(. !=null)' || \
            cat ${resourceManifestPath} | jq '.spec.containers[].envFrom[]?.secretRef.name | select(. !=null)' 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()
    
    if (secrets) {
        secrets.split().each { secret ->
            def secretKeys = sh(
                script: "cat ${secretManifestPath} | jq '.items[] | select(.metadata.name == \"${secret}\").data' 2>/dev/null | grep -iq postgres",
                returnStatus: true
            )
            if (secretKeys == 0) {
                evidence << [
                    type: 'Secret',
                    name: secret,
                    details: 'Contains PostgreSQL-related keys'
                ]
                isUsingPostgres = true
            }
        }
    }
    
    // Check environment variables
    def envVars = sh(
        script: """
            cat ${resourceManifestPath} | jq '.spec.template.spec.containers[].env[]?' 2>/dev/null || \
            cat ${resourceManifestPath} | jq '.spec.containers[].env[]?' 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()
    
    searchStrings.each { host ->
        if (envVars =~ /(?i)${host}/) {
            evidence << [
                type: 'Environment',
                name: "HOST_REFERENCE",
                details: "Found PostgreSQL host reference: ${host}"
            ]
            isUsingPostgres = true
        }
    }
    
    if (envVars =~ /(?i)postgres|pg_/) {
        evidence << [
            type: 'Environment',
            name: 'ENV_VARS',
            details: 'Found PostgreSQL-related environment variables'
        ]
        isUsingPostgres = true
    }
    
    if (isUsingPostgres) {
        FOUND_SERVICES << [
            namespace: namespace,
            resourceType: resourceLabel,
            resourceName: resourceName,
            evidence: evidence
        ]
    }
}

def generateHtmlReport(outputFile) {
    def reportContent = """
        <html>
        <head>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                .namespace { margin-bottom: 30px; }
                .resource { margin-bottom: 20px; padding: 10px; border: 1px solid #ddd; }
                .evidence { margin-left: 20px; color: #666; }
                .summary { background: #f5f5f5; padding: 10px; margin-bottom: 20px; }
            </style>
        </head>
        <body>
            <h1>PostgreSQL Usage Report</h1>
            <div class="summary">
                <h2>Summary</h2>
                <p>Total resources using PostgreSQL: ${FOUND_SERVICES.size()}</p>
                <p>Namespaces affected: ${FOUND_SERVICES.collect { it.namespace }.unique().size()}</p>
            </div>
            
            ${FOUND_SERVICES.groupBy { it.namespace }.collect { namespace, services ->
                """
                <div class="namespace">
                    <h2>Namespace: ${namespace}</h2>
                    ${services.collect { service ->
                        """
                        <div class="resource">
                            <h3>${service.resourceType}: ${service.resourceName}</h3>
                            <div class="evidence">
                                <ul>
                                    ${service.evidence.collect { 
                                        "<li><strong>${it.type}:</strong> ${it.name} - ${it.details}</li>"
                                    }.join('\n')}
                                </ul>
                            </div>
                        </div>
                        """
                    }.join('\n')}
                </div>
                """
            }.join('\n')}
        </body>
        </html>
    """
    
    writeFile file: outputFile, text: reportContent
}

def generateTextSummary(outputFile) {
    def summary = """PostgreSQL Usage Summary
=====================
Total resources: ${FOUND_SERVICES.size()}
Affected namespaces: ${FOUND_SERVICES.collect { it.namespace }.unique().join(', ')}

Detailed Findings:
${FOUND_SERVICES.groupBy { it.namespace }.collect { namespace, services ->
    """
    Namespace: ${namespace}
    ${services.collect { service ->
        """    ${service.resourceType}: ${service.resourceName}
        Evidence:
        ${service.evidence.collect { "        - ${it.type}: ${it.name} - ${it.details}" }.join('\n')}
    """
    }.join('\n')}
    """
}.join('\n')}
"""
    
    writeFile file: outputFile, text: summary
}