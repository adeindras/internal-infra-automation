import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
@Field List<String> resourceFound = []
@Field List<String> resourceNotFound = []

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
def msaResourcesData = params.msaData

def tempDir = "resmsasync${BUILD_NUMBER}"
def buildStopped = false
def slackThread = params.slackThread
def branchIdentifier = ""
def prHtmlLink = ""
def slackFinalStatus = "SUCCESS"

def (customer, project, environment) = targetEnvironmentName.split('-')
def envDirectory
def awsAccountId
def awsRegion
def branchName
def commitMessage
def hasChanges

def resourceHandlerConsoleBanner(type, dir, name, instance) {
    printf(":::: Processing %s data inside %s", type, dir);
    printf(":: name: <%s>", name);
    printf(":: instance: <%s>", instance);
    println("::::");
}

def hclEdit(file, name, key, value) {
    def fileNew = file?.endsWith("/terragrunt.hcl") ? file : "${file}/terragrunt.hcl"

    sh """
        sed -i "s/inputs\\s*=\\s*{/inputs{/g" ${fileNew}
        hcledit -f ${fileNew} attribute set inputs.${key} '\"${value}\"' -u || true
        sed -i "s/inputs\\s*{/inputs={/g" ${fileNew}
        hcledit -f ${fileNew} fmt -u
    """

    resourceFound.push("${file} (${name})")
}

def hclEditDocDBServerlessScalingConfiguration(file, name, minCapacity, maxCapacity) {
    sh """
        sed -i "s/inputs\\s*=\\s*{/inputs{/g" ${file}
        sed -i "s/serverless_v2_scaling_configuration\\s*=\\s*{/serverless_v2_scaling_configuration{/g" ${file}
        hcledit -f ${file} attribute set inputs.serverless_v2_scaling_configuration.min_capacity '${minCapacity}' -u || true
        hcledit -f ${file} attribute set inputs.serverless_v2_scaling_configuration.max_capacity '${maxCapacity}' -u || true
        sed -i "s/inputs\\s*{/inputs={/g" ${file}
        sed -i "s/serverless_v2_scaling_configuration\\s*{/serverless_v2_scaling_configuration={/g" ${file}
        hcledit -f ${file} fmt -u
    """

    resourceFound.push("${file} (${name})")
}

def hclEditRdsAuroraProvisioned(file, name, key, value) {
    def (minCapacity, maxCapacity) = value.split('/')
    sh """
        sed -i "s/inputs\\s*=\\s*{/inputs{/g" ${file}
        sed -i "s/${key}\\s*=\\s*{/${key}{/g" ${file}
        hcledit -f ${file} attribute set inputs.${key}.min_capacity '${minCapacity}' -u || true
        hcledit -f ${file} attribute set inputs.${key}.max_capacity '${maxCapacity}' -u || true
        sed -i "s/inputs\\s*{/inputs={/g" ${file}
        sed -i "s/${key}\\s*{/${key}={/g" ${file}
        hcledit -f ${file} fmt -u
    """

    resourceFound.push("${file} (${name})")
}

def hclEditDocDbElastic(file, name, attribute) {
    def shards = attribute.shards?.trim() ?: "null"
    def vcpu = attribute.vcpu?.trim() ?: "null"

    sh """
        sed -i "s/inputs\\s*=\\s*{/inputs{/g" ${file}
        hcledit -f ${file} attribute set inputs.docdb_shard_count '${shards}' -u || true
        hcledit -f ${file} attribute set inputs.docdb_shard_capacity '${vcpu}' -u || true
        sed -i "s/inputs\\s*{/inputs={/g" ${file}
        hcledit -f ${file} fmt -u
    """

    resourceFound.push("${file} (${name})")
}

def handleDocdbSync(type, data, directory) {
    dir(directory) {
        data.each {
            resourceHandlerConsoleBanner(type, directory, it.name, it.instance)
            def tgDirectory = ""
            if (it.instance == "elastic") {
                tgDirectory = sh(
                    returnStdout: true,
                    script: "ls docdb-elastic | grep ^${it.name} || true")
                    .trim()
                tgDirectory = (tgDirectory == "") ? "docdb-elastic" : "docdb-elastic/${tgDirectory}"
            } else {
                switch (it.name) {
                    case "shared":
                        tgDirectory = sh(
                            returnStdout: true,
                            script: 'ls docdb | grep ^justice-shared || true')
                            .trim()
                        tgDirectory = (tgDirectory == "") ? "docdb" : "docdb/${tgDirectory}"
                        break;
                    case "all":
                        tgDirectory = sh(
                            returnStdout: true,
                            script: 'ls docdb | grep ^justice-shared || true')
                            .trim()
                        tgDirectory = (tgDirectory == "") ? "docdb" : "docdb/${tgDirectory}"
                        break;
                    default:
                        tgDirectory = sh(
                            returnStdout: true,
                            script: "ls docdb | grep ^${it.name} || true")
                            .trim()
                        tgDirectory = "docdb/${tgDirectory}"
                        if (tgDirectory == "") {
                            tgDirectory = sh(
                                returnStdout: true,
                                script: "ls docdb-dedicated | grep ^${it.name} || true")
                                .trim()
                            tgDirectory = (tgDirectory == "") ? "docdb-dedicated" : "docdb-dedicated/${tgDirectory}"
                        }
                        break;
                }
            }
            if (tgDirectory && tgDirectory != "docdb/" && tgDirectory != "docdb-elastic/") {
                if (it.instance == "elastic") {
                    if (it.attributes != null) {
                        hclEditDocDbElastic("${tgDirectory}/terragrunt.hcl", it.name, it.attributes)
                    } else {
                        println("Skipping ${it.name}: attributes is null.")
                        resourceNotFound.push("${tgDirectory} (${it.name})")
                    }
                } else if (it.instance == "db.serverless") {
                    // Only support DCU scaling for now, assuming that the cluster currently has active serverless instances.
                    hclEditDocDBServerlessScalingConfiguration("${tgDirectory}/terragrunt.hcl", it.name, it.attributes.minCapacity, it.attributes.maxCapacity)
                    // TODO: Add provisioned->serverless logic

                } else {
                    hclEdit("${tgDirectory}/terragrunt.hcl", it.name, "instance_class", it.instance)
                }
            } else {
                def label = tgDirectory ? tgDirectory : "docdb"
                resourceNotFound.push("${label} (${it.name})")
                println("Skipping ${label} update: ${it.name} directory not found.")
            }
        }
    }
}

def handleRdsAuroraSync(type, data, directory, auroraType) {
    dir(directory) {
        data.each {
            resourceHandlerConsoleBanner(type, directory, it.name, it.instance)
            def tgDirectory = ""
            switch (it.name) {
                case "shared":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls rds_aurora/${auroraType} | grep ^justice-shared || true")
                        .trim()
                    tgDirectory = "rds_aurora/${auroraType}/${tgDirectory}"
                    break;
                case "all":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls rds_aurora/${auroraType} | grep ^justice-shared || true")
                        .trim()
                    tgDirectory = "rds_aurora/${auroraType}/${tgDirectory}"
                    break;
                case "analytics":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls rds_aurora/${auroraType} | grep ^analytics-shared || true")
                        .trim()
                    tgDirectory = "rds_aurora/${auroraType}/${tgDirectory}"
                    break;
                default:
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls rds_aurora/${auroraType} | grep ^${it.name} || true")
                        .trim()
                    tgDirectory = "rds_aurora/${auroraType}/${tgDirectory}"
                    break;
            }
            if (tgDirectory && tgDirectory != "rds_aurora/${auroraType}/") {
                resourceFound.push("${tgDirectory} (${it.name})")
                if (auroraType == "serverless") {
                    hclEditRdsAuroraProvisioned("${tgDirectory}/terragrunt.hcl", it.name, "serverlessv2_scaling_configuration", it.instance)
                }
                if (auroraType == "provisioned") {
                    hclEdit("${tgDirectory}/terragrunt.hcl", it.name, "instance_class", it.instance)
                }
            } else {
                def label = tgDirectory ? tgDirectory : "rds_aurora"
                resourceNotFound.push("${tgDirectory} (${it.name})")
                println("Skipping ${label} update: ${it.name} directory not found.")
            }
        }
    }
}

def handleRdsSync(type, data, directory) {
    dir(directory) {
        data.each {
            resourceHandlerConsoleBanner(type, directory, it.name, it.instance)
            def tgDirectory = ""
            switch (it.name) {
                case "shared":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls rds | grep ^justice-shared || true')
                        .trim()
                    tgDirectory = "rds/${tgDirectory}"
                    break;
                case "all":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls rds | grep ^justice-shared || true')
                        .trim()
                    tgDirectory = "rds/${tgDirectory}"
                    break;
                case "analytics":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls rds | grep ^analytics-shared || true')
                        .trim()
                    tgDirectory = "rds/${tgDirectory}"
                    break;
                default:
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls rds | grep ^${it.name} || true")
                        .trim()
                    tgDirectory = "rds/${tgDirectory}"
                    break;
            }
            if (tgDirectory && tgDirectory != "rds/") {
                hclEdit("${tgDirectory}/terragrunt.hcl", it.name, "rds_instance_class", it.instance)
            } else {
                def label = tgDirectory ? tgDirectory : "rds"
                resourceNotFound.push("${label} (${it.name})")
                println("Skipping ${label} update: ${it.name} directory not found.")
            }
        }
    }
}

def handleMskSync(type, data, directory) {
    dir(directory) {
        data.each {
            resourceHandlerConsoleBanner(type, directory, it.name, it.instance)
            def tgDirectory = ""
            switch (it.name) {
                case "shared":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls -td msk/justice-shared* 2>/dev/null | head -n 1 || true')
                        .trim()
                    break;
                case "all":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls -td msk/justice-shared* 2>/dev/null | head -n 1 || true')
                        .trim()
                    break;
                default:
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls -td msk/${it.name}* 2>/dev/null | head -n 1 || true')
                        .trim()
                    break;
            }
            if (tgDirectory && tgDirectory != "msk/") {
                hclEdit("${tgDirectory}/terragrunt.hcl", it.name, "broker_instance_type", it.instance)
            } else {
                def label = tgDirectory ? tgDirectory : "msk"
                resourceNotFound.push("${label} (${it.name})")
                println("Skipping ${label} update: ${it.name} directory not found.")
            }
        }
    }
}

def handleElasticacheSync(type, data, directory) {
    dir(directory) {
        data.each {
            resourceHandlerConsoleBanner(type, directory, it.name, it.instance)
            def tgDirectory = ""
            switch (it.name) {
                case "shared":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls -td elasticache/justice-shared* 2>/dev/null | head -n 1 || true')
                        .trim()
                    break;
                case "all":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls -td elasticache/justice-shared* 2>/dev/null | head -n 1 || true')
                        .trim()
                    break;
                default:
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls -td elasticache/${it.name}* 2>/dev/null | head -n 1 || true")
                        .trim()
                    break;
            }
            if (tgDirectory && tgDirectory != "elasticache/") {
                hclEdit("${tgDirectory}/terragrunt.hcl", it.name, "instance_type", it.instance)
            } else {
                def label = tgDirectory ? tgDirectory : "elasticache"
                resourceNotFound.push("${label} (${it.name})")
                println("Skipping ${label} update: ${it.name} directory not found.")
            }
        }
    }
}

def handleOpensearchSync(type, data, directory) {
    dir(directory) {
        data.each {
            resourceHandlerConsoleBanner(type, directory, it.name, it.instance)
            def tgDirectory = ""
            switch (it.name) {
                case "shared":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls opensearch | grep ^logging || true')
                        .trim()
                    tgDirectory = "opensearch/${tgDirectory}"
                    break;
                case "all":
                    tgDirectory = sh(
                        returnStdout: true,
                        script: 'ls opensearch | grep ^logging || true')
                        .trim()
                    tgDirectory = "opensearch/${tgDirectory}"
                    break;
                default:
                    tgDirectory = sh(
                        returnStdout: true,
                        script: "ls opensearch | grep ^${it.name} || true")
                        .trim()
                    tgDirectory = "opensearch/${tgDirectory}"
                    break;
            }
            if (tgDirectory && tgDirectory != "opensearch/") {
                hclEdit("${tgDirectory}/terragrunt.hcl", it.name, "instance_type", it.instance)
            } else {
                def label = tgDirectory ? tgDirectory : "opensearch"
                resourceNotFound.push("${label} (${it.name})")
                println("Skipping ${label} update: ${it.name} directory not found.")
            }
        }
    }
}

node('infra-sizing') {
    container('tool') {
        try {
            stage('Check Params'){
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
                if (msaResourcesData == '') {
                    currentBuild.result = 'NOT_BUILT'
                    buildStopped = true
                    error('Aborting the build')
                }
                if (identifier == '') {
                    currentBuild.result = 'NOT_BUILT'
                    buildStopped = true
                    error('Aborting the build')
                }
                if (!customer || !project || !environment) {
                    currentBuild.result = 'FAILURE'
                }
            }
            stage('Checkout IAC Repo') {
                dir(tempDir) {
                    sshagent(['bitbucket-repo-read-only']) {
                        sh """
                            GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
                            git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/iac.git" || true
                            chown -R 1000:1000 iac
                        """
                    }
                }
            }
            stage("Populating variables") {
                dir(tempDir) {
                    dir("iac") {
                        envDirectory = sh(
                            returnStdout: true,
                            script: """
                            clusterDir=\$(find ./live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep "${environment}\\/" | grep -v terragrunt-cache)
                            dirname \${clusterDir}
                            """).trim()

                        awsAccountId = sh(
                            returnStdout: true,
                            script: """
                            echo ${envDirectory} | egrep -o '[[:digit:]]{12}'
                            """).trim()

                        awsRegion = sh(
                            returnStdout: true,
                            script: """
                            basename \$(dirname ${envDirectory})
                            """).trim()
                    }
                    branchName = "${branchIdentifier}autorightsizing-${awsAccountId}-${targetEnvironmentName}-${identifier}"
                    commitMessage = "feat: ${branchIdentifier}${targetEnvironmentName} autorightsizing to ${targetCCU} CCU"
                }
            }
            stage("Processing Data") {
                dir(tempDir) {
                    dir("iac") {
                        def jsonSlurper = new JsonSlurper()
                        def resources = jsonSlurper.parseText(msaResourcesData)
                        resources.resources.each{
                            switch(it.type) {            
                                case "docdb": 
                                    handleDocdbSync(it.type, it.data, envDirectory)
                                    break; 
                                case "rds": 
                                    handleRdsSync(it.type, it.data, envDirectory)
                                    break;
                                case "rdsauroraprovisioned": 
                                    handleRdsAuroraSync(it.type, it.data, envDirectory, "provisioned")
                                    break;
                                case "rdsauroraserverless": 
                                    handleRdsAuroraSync(it.type, it.data, envDirectory, "serverless")
                                    break;
                                case "msk": 
                                    handleMskSync(it.type, it.data, envDirectory)
                                    break;
                                case "elasticache": 
                                    handleElasticacheSync(it.type, it.data, envDirectory)
                                    break;
                                case "opensearch": 
                                    handleOpensearchSync(it.type, it.data, envDirectory)
                                    break;
                                default:
                                    printf("Unhandled type: %s", it.type)
                                    break;
                            }
                        }
                    }
                }
            }
            stage("Commit & Push"){
                dir(tempDir) {
                    dir("iac") {
                        sh "git config --global --add safe.directory '*'"
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
                        } else {
                            println("No changes. No commit needed.");
                        }
                    }
                }
            }

            if (hasChanges == "true") {
                stage("Create PR") {
                    prSummary = """
        :: CCU: ${targetCCU}

        :: Resource found:
        ${resourceFound.collect { "- $it" }.join('\n        ')}

        :: Resource not found:
        ${resourceNotFound.collect { "- $it" }.join('\n        ')}
                    """
                    withCredentials([string(credentialsId: "BitbucketAppKeyUserPassB64", variable: 'BitbucketAppKeyUserPassB64')]) {
                        // POST
                        def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
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
                                    name: "master"
                                ]
                            ],
                            summary: [
                                raw: "${prSummary}"
                            ],
                            close_source_branch: true
                        ]
                        def jsonPayload = JsonOutput.toJson(postData)
                        post.setRequestMethod("POST")
                        post.setDoOutput(true)
                        post.setRequestProperty("Content-Type", "application/json")
                        post.setRequestProperty("Authorization", "Basic ${BitbucketAppKeyUserPassB64}")
                        post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                        def postRC = post.getResponseCode();
                        if(postRC.equals(200) || postRC.equals(201)) {
                            def jsonSlurper = new JsonSlurper()
                            def reply = post.getInputStream().getText()
                            def replyMap = jsonSlurper.parseText(reply)
                            prHtmlLink = replyMap.links.html.href
                        }
                        println(prHtmlLink);
                    }
                }
            } else {
              println("No changes. PR not created.");
            }
        } catch (SkipException ex) {
            currentBuild.result = 'ABORTED'
            currentBuild.displayName = "${currentBuild.displayName} - [Skipped]"
            echo "Build is skipped:\n\t${ex.message}"
            slackFinalStatus = currentBuild.result
        } catch (InterruptedException err) {
            currentBuild.result = 'ABORTED'
            slackFinalStatus = currentBuild.result
        } catch (Exception err) {
            echo "Exception Thrown:\n\t${err}"
            currentBuild.result = 'FAILURE'
            slackFinalStatus = currentBuild.result
            // TODO: Send notif to slack channel
        } finally {
            stage('Sending Slack Notification'){
                def elapsedTime = currentBuild.durationString.replaceAll(' and counting', "")
                def nResourcesFound = resourceFound.size()
                def nResourcesNotFound = resourceNotFound.size()
                if (hasChanges == "true") {
                prLink="<${prHtmlLink}/console|Check PR!>"
                prSummary="""
    :: Total processed resources: \n $nResourcesFound \n \n
    :: Total not processed resources: \n $nResourcesNotFound \n \n
    :: Not processed resources (PLEASE CHECK IT MANUALLY): \n $resourceNotFound \n \n
                """} else {
                prLink="-"
                prSummary="No changes."}
                withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                    // POST
                    def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                    def postData =  [
                        channel: "C079A11910R",
                        blocks: [
                            [
                                type: "section",
                                text: [
                                    type: "mrkdwn",
                                    text: ":tf: ${slackFinalStatus} Sync AWS resources with MSA data job:"
                                ]
                            ],
                            [
                                type: "section",
                                fields: [
                                    [
                                        type: "mrkdwn",
                                        text: "*Jenkins:*\n<${BUILD_URL}/console|Go to Jenkins!>"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*PR:*\n${prLink}"
                                    ],
                                    [
                                        type: 'mrkdwn',
                                        text: "*Summary:*\n${prSummary}"
                                    ],
                                    [
                                        type: "mrkdwn",
                                        text: "*Execution Time:*\n${elapsedTime}"
                                    ],
                                ]
                            ]
                        ],
                        thread_ts: "${slackThread}"
                    ]
                    def jsonPayload = JsonOutput.toJson(postData)
                    post.setRequestMethod("POST")
                    post.setDoOutput(true)
                    post.setRequestProperty("Content-Type", "application/json")
                    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
                    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
                    def postRC = post.getResponseCode();
                    println(postRC);
                    if(postRC.equals(200) || postRC.equals(201)) {
                        println(post.getInputStream().getText())
                    }
                }
            }
        }
    }
}


// Hacky way to skip later stages
public class SkipException extends Exception {
    public SkipException(String errorMessage) {
        super(errorMessage);
    }
}
