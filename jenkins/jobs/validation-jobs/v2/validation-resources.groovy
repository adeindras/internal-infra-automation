import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
  [
    parameters([
      string(defaultValue: '', name: 'targetCCU'),
      string(defaultValue: '', name: 'targetEnvironmentName'),
      string(defaultValue: '', name: 'slackThread'),
      string(defaultValue: '', name: 'msaData')
    ])
  ]
)

// constants
String targetCCU = params.targetCCU
String targetEnvironmentName = params.targetEnvironmentName
String msaResourcesData = params.msaData
String slackThread = params.slackThread
Boolean buildStopped = false
String toolScriptDir = '.'
String envDirectory
String awsAccountId
String awsRegion
String outputDir
Boolean validationStatusOK = false
String slackChannel
String directoryToFind
String tgDirectory
String iacPath = 'iac'
String customService

node('infra-sizing') {
    container('tool') {
        try {
            stage('Check Params') {
                String resultNotBuilt = 'NOT_BUILT'

                if (targetCCU == '') {
                    currentBuild.result = resultNotBuilt
                    buildStopped = true
                    error('targetCCU is empty. Aborting the build')
                }

                if (targetEnvironmentName == '') {
                    currentBuild.result = resultNotBuilt
                    buildStopped = true
                    error('targetEnvironmentName is empty. Aborting the build')
                }

                if (msaResourcesData == '') {
                    currentBuild.result = resultNotBuilt
                    buildStopped = true
                    error('msaData is empty. Aborting the build')
                }

                if (WORKSPACE.contains('DEVELOPMENT')) {
                    slackChannel = 'C07C69NHGTW'
                } else {
                    slackChannel = 'C079A11910R'
                }

                echo targetCCU
                echo targetEnvironmentName
            }

            if (!buildStopped) {
                def (customer, project, environment) = targetEnvironmentName.split('-')
                
                if (!customer || !project || !environment) {
                  currentBuild.result = 'FAILURE'
                }

                dir("resourceValidation${BUILD_NUMBER}") {
                    stage('Checkout IAC Repo') {
                        sshagent(['bitbucket-repo-read-only']) {
                            sh """
                                GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" \
                                git clone --depth 1 --quiet "git@bitbucket.org:accelbyte/iac.git" || true
                                chmod -R 777 ${iacPath} | true
                            """
                        }
                    }

                    stage('Preparing tools') {
                        withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                            sh """
                                BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                    -d jenkins/jobs/scripts/infra-validation \
                                    -r master \
                                    -s internal-infra-automation \
                                    -o \$(pwd)
                                chown -R 1000:1000 jenkins
                            """
                        }

                        toolScriptDir = pwd() + '/jenkins/jobs/scripts/infra-validation'
                        outputDir = toolScriptDir + '/reporting'
                        sh """
                            cat ${outputDir}/output.json | jq --arg env "${targetEnvironmentName}" '.main.environmentName = \$env' > ${outputDir}/output.json.tmp
                            mv ${outputDir}/output.json.tmp ${outputDir}/output.json

                            cat ${outputDir}/output.json | jq --arg env "${BUILD_URL}/console" '.main.jenkinsLink = \$env' > ${outputDir}/output.json.tmp
                            mv ${outputDir}/output.json.tmp ${outputDir}/output.json

                            chmod +x ${toolScriptDir}/*.sh
                        """

                        dir(iacPath) {
                            envDirectory = sh(
                                returnStdout: true,
                                script: """
                                    clusterDir=\$(find ./live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep ${environment} | grep -v terragrunt-cache)
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

                        awsAccessMerged = sh(
                            returnStdout: true,
                            script: """
                                set +x
                                rm -rf ~/.aws/config || true
                                export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
                                \$(aws sts assume-role \\
                                --role-arn arn:aws:iam::${awsAccountId}:role/${targetEnvironmentName}-automation-platform \\
                                --role-session-name ${targetEnvironmentName} \\
                                --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
                                --output text))

                                export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
                                \$(aws sts assume-role \\
                                --role-arn arn:aws:iam::${awsAccountId}:role/${targetEnvironmentName}-automation-platform-terraform \\
                                --role-session-name ${targetEnvironmentName} \\
                                --query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
                                --output text))
                                echo \${AWS_ACCESS_KEY_ID}:\${AWS_SECRET_ACCESS_KEY}:\${AWS_SESSION_TOKEN}
                            """).trim()

                        def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = awsAccessMerged.split(':')
                        env.AWS_ACCESS_KEY_ID = awsAccessKeyId
                        env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey
                        env.AWS_SESSION_TOKEN = awsSessionToken
                        env.AWS_DEFAULT_REGION = awsRegion
                        env.AWS_REGION = awsRegion
                        sh 'aws sts get-caller-identity --no-cli-pager'
                    }

                    stage('Validating resources') {
                        dir(iacPath) {
                            def slurper = new JsonSlurper()
                            def resourcesData = slurper.parseText(msaResourcesData)
                            resourcesData.each {
                                dir("${envDirectory}") {
                                    switch (it.type) {
                                        case 'rds':
                                            it.data.each { rds ->
                                                switch (rds.name) {
                                                    case 'shared':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls rds | grep ^justice-shared || true')
                                                            .trim()
                                                        directoryToFind = 'rds/justice-shared*'
                                                        break
                                                    case 'analytics':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls rds | grep ^analytics-shared || true')
                                                            .trim()
                                                        directoryToFind = 'rds/analytics-shared*'
                                                        break
                                                    default:
                                                    tgDirectory = sh(
                                                        returnStdout: true,
                                                        script: "ls rds | grep ^${rds.name} || true")
                                                        .trim()
                                                    directoryToFind = "rds/${rds.name}*"
                                                    break
                                                }

                                                if (tgDirectory == '') {
                                                    echo "Directory of RDS ${rds.name} not found"
                                                    customService = sh(
                                                        returnStdout: true,
                                                        script: "echo ${rds.name} | grep ^custom- || true")
                                                        .trim()
                                                    if (customService == '') {
                                                        sh """
                                                            OUTPUT_FILE=${outputDir}/output.json \
                                                            EXPECTED_INSTANCE_TYPE=${rds.class} \
                                                            DIRECTORY_NAME=${directoryToFind} \
                                                            RESOURCE_TYPE=RDS \
                                                            ${toolScriptDir}/dir-not-found.sh
                                                        """
                                                    }
                                                } else {
                                                    dir('rds/' + tgDirectory) {
                                                        sh """
                                                            EXPECTED_INSTANCE_TYPE=${rds.class} \
                                                            OUTPUT_FILE=${outputDir}/output.json ${toolScriptDir}/rds.sh
                                                            cat ${outputDir}/output.json
                                                        """
                                                    }
                                                }

                                                if (rds.replicas) {
                                                    rds.replicas.each { -> rdsRepl
                                                        if (rdsRepl.name.contains('shared-replica')) {
                                                            tgDirectory = sh(
                                                                returnStdout: true,
                                                                script: 'ls rds | grep ^justice-replica || true')
                                                                .trim()
                                                            directoryToFind = 'rds/justice-replica*'
                                                        }
                                                    }

                                                    if (tgDirectory == '') {
                                                        echo "Directory of RDS ${rds.name} not found"
                                                        customService = sh(
                                                            returnStdout: true,
                                                            script: "echo ${rds.name} | grep ^custom- || true")
                                                            .trim()
                                                        if (customService == '') {
                                                            sh """
                                                                OUTPUT_FILE=${outputDir}/output.json \
                                                                EXPECTED_INSTANCE_TYPE=${rds.class} \
                                                                DIRECTORY_NAME=${directoryToFind} \
                                                                RESOURCE_TYPE=RDS \
                                                                ${toolScriptDir}/dir-not-found.sh
                                                            """
                                                        }
                                                    } else {
                                                        dir('rds/' + tgDirectory) {
                                                            sh """
                                                                EXPECTED_INSTANCE_TYPE=${rds.class} \
                                                                OUTPUT_FILE=${outputDir}/output.json \
                                                                ${toolScriptDir}/rds.sh
                                                                cat ${outputDir}/output.json
                                                            """
                                                        }
                                                    }
                                                }
                                            }
                                            break
                                        case 'msk':
                                            echo 'Validating MSK'
                                            it.data.each { msk ->
                                                switch (msk.name) {
                                                    case 'all':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls msk | grep ^justice-shared || true')
                                                            .trim()
                                                        directoryToFind = 'msk/justice-shared*'
                                                        break
                                                    case 'shared':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls msk | grep ^justice-shared || true')
                                                            .trim()
                                                        directoryToFind = 'msk/justice-shared*'
                                                        break
                                                    default:
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: "ls msk | grep ^${msk.name} || true")
                                                            .trim()
                                                        directoryToFind = "msk/${msk.name}*"
                                                        break
                                                }

                                                if (tgDirectory == '') {
                                                    echo "Directory of MSK ${msk.name} not found"
                                                    customService = sh(
                                                        returnStdout: true,
                                                        script: "echo ${msk.name} | grep ^custom- || true")
                                                        .trim()
                                                    if (customService == '') {
                                                        sh """
                                                            OUTPUT_FILE=${outputDir}/output.json \
                                                            EXPECTED_INSTANCE_TYPE=${msk.class} \
                                                            DIRECTORY_NAME=${directoryToFind} \
                                                            RESOURCE_TYPE=MSK \
                                                            ${toolScriptDir}/dir-not-found.sh
                                                        """
                                                    }
                                                } else {
                                                    dir('msk/' + tgDirectory) {
                                                        sh """
                                                            EXPECTED_INSTANCE_TYPE=${msk.class} \
                                                            OUTPUT_FILE=${outputDir}/output.json ${toolScriptDir}/msk.sh
                                                            cat ${outputDir}/output.json
                                                        """
                                                    }
                                                }
                                            }
                                            break
                                        case 'docdb':
                                            echo 'Validating DocumentDB'
                                            it.data.each { docdb ->
                                                switch (docdb.name) {
                                                    case 'all':
                                                        tgDirectory = '.'
                                                        break
                                                    case 'shared':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls docdb | grep ^justice-shared || true')
                                                            .trim()
                                                            directoryToFind = 'docdb/justice-shared'
                                                        break
                                                    default:
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: "ls docdb | grep ^${docdb.name} || true")
                                                            .trim()
                                                        directoryToFind = "docdb/${docdb.name}*"
                                                        break
                                                }

                                                if (tgDirectory == '') {
                                                    echo "Directory of DocDB ${docdb.name} not found"
                                                    customService = sh(
                                                        returnStdout: true,
                                                        script: "echo ${docdb.name} | grep ^custom- || true")
                                                        .trim()
                                                    if (customService == '') {
                                                        sh """
                                                            OUTPUT_FILE=${outputDir}/output.json \
                                                            EXPECTED_INSTANCE_TYPE=${docdb.class} \
                                                            DIRECTORY_NAME=${directoryToFind} \
                                                            RESOURCE_TYPE="DOCUMENT DB" \
                                                            ${toolScriptDir}/dir-not-found.sh
                                                        """
                                                    }
                                                } else {
                                                    String docdbDir = 'docdb/'
                                                    
                                                    if (tgDirectory != '.') {
                                                        docdbDir = 'docdb/'
                                                    }

                                                    dir(docdbDir + tgDirectory) {
                                                        sh """
                                                            EXPECTED_INSTANCE_TYPE=${docdb.class} \
                                                            OUTPUT_FILE=${outputDir}/output.json ${toolScriptDir}/docdb.sh
                                                            cat ${outputDir}/output.json
                                                        """
                                                    }
                                                }
                                            }
                                            break
                                        case 'elasticache':
                                            echo 'Validating Elasticache'
                                            it.data.each { redis ->
                                                switch (redis.name) {
                                                    case 'all':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls elasticache | grep ^justice-shared || true')
                                                            .trim()
                                                        directoryToFind = 'elasticache/justice-shared*'
                                                        break
                                                    case 'shared':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls elasticache | grep ^justice-shared || true')
                                                            .trim()
                                                        directoryToFind = 'elasticache/justice-shared*'
                                                        break
                                                    default:
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: "ls elasticache | grep ^${redis.name} || true")
                                                            .trim()
                                                        directoryToFind = "elasticache/${redis.name}*"
                                                        break
                                                }

                                                if (tgDirectory == '') {
                                                    echo "Directory of Elasticache ${redis.name} not found"
                                                    customService = sh(
                                                        returnStdout: true,
                                                        script: "echo ${redis.name} | grep ^custom- || true")
                                                        .trim()
                                                    if (customService == '') {
                                                        sh """
                                                            OUTPUT_FILE=${outputDir}/output.json \
                                                            EXPECTED_INSTANCE_TYPE=${redis.class} \
                                                            DIRECTORY_NAME=${directoryToFind} \
                                                            RESOURCE_TYPE=Elasticache \
                                                            ${toolScriptDir}/dir-not-found.sh
                                                        """
                                                    }
                                                } else {
                                                    dir('elasticache/' + tgDirectory) {
                                                        sh """
                                                            EXPECTED_INSTANCE_TYPE=${redis.class} \
                                                            OUTPUT_FILE=${outputDir}/output.json ${toolScriptDir}/elasticache.sh
                                                            cat ${outputDir}/output.json
                                                        """
                                                    }
                                                }
                                            }
                                            break
                                        case 'opensearch':
                                            echo 'Validating Opensearch'
                                            it.data.each { opensearch ->
                                                switch (opensearch.name) {
                                                    case 'all':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls opensearch | grep ^logging || true')
                                                            .trim()
                                                        directoryToFind = 'opensearch/logging*'
                                                        break
                                                    case 'shared':
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: 'ls opensearch | grep ^logging || true')
                                                            .trim()
                                                        directoryToFind = 'opensearch/logging*'
                                                        break
                                                    default:
                                                        tgDirectory = sh(
                                                            returnStdout: true,
                                                            script: "ls opensearch | grep ^${opensearch.name} || true")
                                                            .trim()
                                                        directoryToFind = "opensearch/${opensearch.name}*"
                                                        break
                                                }

                                                if (tgDirectory == '') {
                                                    echo "Directory of Opensearch ${opensearch.name} not found"
                                                    customService = sh(
                                                        returnStdout: true,
                                                        script: "echo ${opensearch.name} | grep ^custom- || true")
                                                        .trim()
                                                    if (customService == '') {
                                                        sh """
                                                            OUTPUT_FILE=${outputDir}/output.json \
                                                            EXPECTED_INSTANCE_TYPE=${opensearch.class} \
                                                            DIRECTORY_NAME=${directoryToFind} \
                                                            RESOURCE_TYPE=Opensearch \
                                                            ${toolScriptDir}/dir-not-found.sh
                                                        """
                                                    }
                                                } else {
                                                    dir('opensearch/' + tgDirectory) {
                                                        sh """
                                                            EXPECTED_INSTANCE_TYPE=${opensearch.class} \
                                                            OUTPUT_FILE=${outputDir}/output.json ${toolScriptDir}/opensearch.sh
                                                            cat ${outputDir}/output.json
                                                        """
                                                    }
                                                }
                                            }
                                            break
                                    }
                                }
                            }
                            String validationStatusOutput = sh(
                                returnStdout: true,
                                script: """
                                    cat ${outputDir}/output.json | jq '.validationOutput[] | select(.validationStatus=="false")'
                                """).trim()
                            if (validationStatusOutput == '') {
                                validationStatusOK = true
                            }
                        }
                    }

                    stage('Generate Report') {
                        publishHTML(
                            target: [
                                reportDir: "${outputDir}",
                                reportFiles: 'index.html',
                                reportName: "${targetEnvironmentName}_MSAValidation",
                                alwaysLinkToLastBuild: true,
                                allowMissing: true,
                                keepAll: true
                            ]
                        )
                    }
              }
            }
        } finally {
            if (slackThread != '') {
                stage('Send Notification') {
                    withCredentials([string(credentialsId: 'rightsize-validator-slack-token', variable: 'slackToken')]) {
                        // POST
                        def post = new URL('https://slack.com/api/chat.postMessage').openConnection()
                        def postData =  [
                            channel: slackChannel,
                            blocks: [
                                [
                                    type: 'section',
                                    text: [
                                    type: 'mrkdwn',
                                    text: ":tf: AWS resources validation done with success status = ${validationStatusOK}. <${BUILD_URL}/${targetEnvironmentName}_5fMSAValidation/|Open Report Page!>"
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
                        println(postRC)
                        if (postRC.equals(200) || postRC.equals(201)) {
                            println(post.getInputStream().getText())
                        }
                    }
                }
            }

            if (!validationStatusOK) {
                currentBuild.result = 'FAILURE'
            }
        }
    }
}
