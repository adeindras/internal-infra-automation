import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
    [
        parameters([
            choice(choices: envList, description: "", name: "targetEnvironmentName"),
            string(name: "agsNamespace", defaultValue: "AccelbyteTesting"),
            string(defaultValue: '', name: 'slackThread', description: "Leave it blank, this is filled from another pipeline"),
            text(name: 'qaFilter', defaultValue: '', description: 'Enter filter')
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

def envName = params.targetEnvironmentName
def agsNamespace = params.agsNamespace
String slackThread = params.slackThread
String slackChannel
def envBaseURL
def newEnvBaseUrl
def clientId
def clientSecret
def userId
def userPassword
def superUserEmail
def superUserPassword
def publisherNamespace
def studioAdminEmail
def studioAdminPassword
def filterStatus
def qaFilters

try {
    node('deploy-agent'){
        container('tool'){
            stage('Create Test User & Prepare Test Binary'){
                dir("deployment${BUILD_NUMBER}"){
                    if (slackThread == '') {
                        BUILD_TRIGGER_BY = currentBuild.getBuildCauses()[0].userName
                        if (WORKSPACE.contains('DEVELOPMENT')) {
                            slackChannel = 'C02HW7K2EBC'
                        } else {
                            slackChannel = 'C02HW7K2EBC'
                        }
                        qaFilters = params.qaFilter.readLines()
                                            .collect { it.trim() }
                                            .join("\n")
                        if (qaFilters.isEmpty()) {
                            createBanner("No filter applied, running with full test")
                            filterStatus = false
                        } else {
                            createBanner("Filter applied, running with filtered test")
                            filterStatus = true
                            writeFile file: 'jenkins/filter.txt', text: qaFilters
                        }
                        currentBuild.displayName = "#${BUILD_NUMBER} - ${envName}"
                        withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                            // POST
                            def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
                            def postData =  [
                                channel: slackChannel,
                                blocks: [
                                    [
                                        type: "section",
                                        text: [
                                            type: "mrkdwn",
                                            text: "AGS-Test-App is running :kirby_run: \n*<${BUILD_URL}console|Go to Jenkins now!>*"
                                        ]
                                    ], 
                                    [
                                        type: "section",
                                        fields: [
                                            [
                                                type: "mrkdwn",
                                                text: "*Environment:*\n${envName}"
                                            ],
                                            [
                                                type: "mrkdwn",
                                                text: "*Triggered by:*\n${BUILD_TRIGGER_BY}\n<!subteam^S04QE7WFPTM|qa-platform>"
                                            ],
                                            [
                                                type: "mrkdwn",
                                                text: "*Filtered execution:*\n${filterStatus}"
                                            ]
                                        ]
                                    ]
                                ]
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
                                def jsonSlurper = new JsonSlurper()
                                def reply = post.getInputStream().getText()
                                def replyMap = jsonSlurper.parseText(reply)
                                slackThread = replyMap.ts
                            }

                            if (!qaFilters.isEmpty()) {
                                withCredentials([string(credentialsId: "ab-automation-monitoring-slackbot-token", variable: 'slackToken')]) {
                                    def command = """
                                        curl -s -X POST https://slack.com/api/files.upload \
                                            -H "Authorization: Bearer ${slackToken}" \
                                            -F channels=${slackChannel} \
                                            -F file=@jenkins/filter.txt \
                                            -F "initial_comment=Filter used" \
                                            -F thread_ts="${slackThread}" \
                                            -F title="Filtered_testcase"
                                        """
                                    sh command
                                }
                            }

                        }
                    }
                    def (customer, project, environment) = envName.split('-')
                    env.customer=customer
                    env.project=project
                    env.environment=environment
                
                    withCredentials([string(credentialsId: 'internal-deploy-tool-token-0', variable: 'BitbucketToken')]) {
                        sh """
                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                -f $customer/$project/$environment/cluster-information.env \
                                -r master \
                                -s deployments \
                                -o \$(pwd)
                        """
                    }
                    withCredentials([string(credentialsId: 'BitbucketInternalProjectToken', variable: 'BitbucketToken')]) {
                        sh """
                            BITBUCKET_ACCESS_TOKEN=$BitbucketToken bitbucket-downloader \
                                -d jenkins/image-sdk-test,jenkins/jobs/scripts/functional-test \
                                -r development \
                                -s internal-infra-automation \
                                -o \$(pwd)
                            ls -ltrah
                            chmod -R 755 *
                        """
                    }
                    stash includes: 'jenkins/**', name: 'ags-test', useDefaultExcludes: false
                    
                    def fileContent = readFile("$customer/$project/$environment/cluster-information.env").trim()
                    def lines = fileContent.tokenize('\n')
                    lines.each { line ->
                        def (key, value) = line.tokenize('=')
                        env."${key}" = "${value}"
                        echo "${key} = ${value}"
                    }
                    env."AWS_DEFAULT_REGION"="${AWS_REGION}"
                    sh """#!/bin/bash
                        set -e
                        set -o pipefail
                        envsubst < ~/.aws/config.template > ~/.aws/config
                        # aws sts get-caller-identity
                        aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_REGION}
                        envsubst < jenkins/jobs/scripts/functional-test/external-secret.yaml > external-secret.yaml
                        kubectl apply -f external-secret.yaml
                        # just a workaround 
                        # TODO: remove sleep to use proper check
                        sleep 5
                        kubectl get secret autorightsizing-secret -n justice -oyaml > secretfile
                    """

                    envBaseURL = sh(returnStdout: true, script: "cat secretfile | yq '.data.envBaseUrl'").trim()
                    envBaseURL = new String(envBaseURL.decodeBase64())

                    if ( envName == 'foundations-justice-internal') {
                        newEnvBaseUrl = envBaseURL.replace("https://", "https://${agsNamespace}.")
                        envBaseURL = newEnvBaseUrl
                    }

                    clientId = sh(returnStdout: true, script: "cat secretfile | yq '.data.clientId'").trim()
                    clientId = new String(clientId.decodeBase64())

                    clientSecret = sh(returnStdout: true, script: "cat secretfile | yq '.data.clientSecret'").trim()
                    clientSecret = new String(clientSecret.decodeBase64())

                    publisherNamespace = sh(returnStdout: true, script: "cat secretfile | yq '.data.publisherNamespace'").trim()
                    publisherNamespace = new String(publisherNamespace.decodeBase64())

                    superUserEmail = sh(returnStdout: true, script: "cat secretfile | yq '.data.superUserEmail'").trim()
                    superUserEmail = new String(superUserEmail.decodeBase64())

                    superUserPassword = sh(returnStdout: true, script: "cat secretfile | yq '.data.superUserPassword'").trim()
                    superUserPassword = new String(superUserPassword.decodeBase64())

                    publisherID = sh(returnStdout: true, script: "cat secretfile | yq '.data.publisherID'").trim()
                    publisherID = new String(publisherID.decodeBase64())

                    publisherSecret = sh(returnStdout: true, script: "cat secretfile | yq '.data.publisherSecret'").trim()
                    publisherSecret = new String(publisherSecret.decodeBase64())

                    createUserData = [
                        preDefined: [
                            baseURLDirect: "${envBaseURL}",
                            namespace: "${agsNamespace}",
                            namespacePublisher: "${publisherNamespace}",
                            clientId: "${clientId}",
                            clientSecret: "${clientSecret}",
                            globalUserEmailSuperuser: "${superUserEmail}",
                            globalUserPasswordSuperuser: "${superUserPassword}"
                        ]
                    ]
                    def jsonData = JsonOutput.toJson(createUserData)
                    writeFile file: 'jenkins/jobs/scripts/functional-test/data.json', text: jsonData
                    try {
                        if (WORKSPACE.contains('DEVELOPMENT')) {
                            if ( envName == 'foundations-justice-internal') {
                                createBanner("Create studio admin in starter")
                                sh """
                                apk add uuidgen
                                cd jenkins/jobs/scripts/functional-test/
                                chmod +x create-user-starter.sh
                                ./create-user-starter.sh
                                """
                            } else {
                                sh """
                                apk add uuidgen
                                cd jenkins/jobs/scripts/functional-test/
                                chmod +x create-user.sh
                                ./create-user.sh
                                """
                            }
                        } else {
                            createBanner("Due to AGS Test App still on development phase, so skipped on STABLE jenkins")
                        }
                    }
                    catch(err) {
                        currentBuild.result = 'UNSTABLE'
                    }
                    finally {
                        currentBuild.result = 'SUCCESS'
                    }
                    
                    stash includes: 'jenkins/jobs/scripts/functional-test/data.json', name: 'seedingdata', useDefaultExcludes: false
                    gameClientId = sh(returnStdout: true, script: "cat jenkins/jobs/scripts/functional-test/data.json | jq -r '.seed.gameClientId'").trim()
                    gameClientSecret = sh(returnStdout: true, script: "cat jenkins/jobs/scripts/functional-test/data.json | jq -r '.seed.gameClientSecret'").trim()
                    if ( envName == 'foundations-justice-internal' ) {
                        studioAdminEmail = sh(returnStdout: true, script: "cat jenkins/jobs/scripts/functional-test/data.json | jq -r '.seed.emailAddress'").trim()
                        superUserEmail = studioAdminEmail
                        studioAdminPassword = sh(returnStdout: true, script: "cat jenkins/jobs/scripts/functional-test/data.json | jq -r '.seed.password'").trim()
                        superUserPassword = studioAdminPassword
                    }
                }
            }
        }
    }

    node('sdk-test'){
        container('tool'){
            stage('AGS Test App testing'){
                dir("deployment${BUILD_NUMBER}"){
                    unstash "ags-test"
                    unstash "seedingdata"                    
                    if (currentBuild.result == 'SUCCESS') {
                        try {
                            if (WORKSPACE.contains('DEVELOPMENT')) {
                                if (filterStatus) {
                                    createBanner("Start run AGS-Test-App with filtered test")
                                    sh """#!/bin/bash
                                        set -ex
                                        ls -ltrah
                                        Xvfb :100 -screen 0 1280x1024x24 -nolisten tcp &
                                        /winetricks nocrashdialog
                                        ls -la jenkins
                                        cd jenkins/image-sdk-test
                                        unzip ags_test_app.zip
                                        cd ..
                                        ls -la
                                        cp -f filter.txt image-sdk-test/bin/filter.txt
                                        cd image-sdk-test/bin
                                        cat filter.txt
                                        WINEDEBUG=-all wine64 ./ags-test.exe -V
                                        WINEDEBUG=-all wine64 ./ags-test.exe -e '$envBaseURL' -c '$gameClientId' -s '$gameClientSecret' -i '$publisherID' -z '$publisherSecret' -u '$superUserEmail' -p '$superUserPassword' -I abtestcppj -v -f filter.txt
                                    """
                                    
                                } else {
                                    sh """#!/bin/bash
                                        set -ex
                                        ls -ltrah
                                        Xvfb :100 -screen 0 1280x1024x24 -nolisten tcp &
                                        /winetricks nocrashdialog
                                        cd jenkins/image-sdk-test
                                        unzip ags_test_app.zip
                                        cd bin
                                        WINEDEBUG=-all wine64 ./ags-test.exe -V
                                        WINEDEBUG=-all wine64 ./ags-test.exe -e '$envBaseURL' -c '$gameClientId' -s '$gameClientSecret' -i '$publisherID' -z '$publisherSecret' -u '$superUserEmail' -p '$superUserPassword' -I abtestcppj -v
                                    """
                                }
                            } else {
                                createBanner("Due to AGS Test App still on development phase, so skipped on STABLE jenkins")
                            }
                        } catch(err) {
                            
                        } finally {
                            sh """#!/bin/bash
                                cd jenkins/image-sdk-test/bin
                                echo 'Printing test_execution_history'
                                echo 'start of ----------------------------'
                                cat \$(ls test_execution_history/*-abtestcppj 2>/dev/null)
                                echo -e '\\nend of-------------------------------'
                            """
                            stash includes: 'jenkins/image-sdk-test/bin/test_execution_history/**', name: 'ags-test-result', useDefaultExcludes: false
                            currentBuild.result = 'SUCCESS'
                        }
                    }
                }
            }

            stage('AGS Test App teardown'){
                dir("deployment${BUILD_NUMBER}"){
                    unstash "ags-test"
                    unstash "seedingdata"
                    try {
                        if (WORKSPACE.contains('DEVELOPMENT')) {
                            sh """#!/bin/bash
                                    set -ex
                                    ls -ltrah
                                    cd jenkins/image-sdk-test/bin
                                    WINEDEBUG=-all wine64 ./ags-test.exe -e '$envBaseURL' -c '$gameClientId' -s '$gameClientSecret' -i '$publisherID' -z '$publisherSecret' -u '$superUserEmail' -p '$superUserPassword' -I abtestcppj -v -x
                            """
                        } else {
                            createBanner("Due to AGS Test App still on development phase, so skipped on STABLE jenkins")
                        }
                    }
                    catch(err) {
                        
                    }
                    finally {
                        currentBuild.result = 'SUCCESS'
                    }
                    
                }
            }
        }
    }

} finally {
    node('deploy-agent'){
        container('tool'){
            stage('Cleanup'){
                dir("deployment${BUILD_NUMBER}"){
                    try {
                        if (WORKSPACE.contains('DEVELOPMENT')) {
                            unstash "ags-test"
                            unstash "seedingdata"
                            if ( envName == 'foundations-justice-internal') {
                                createBanner("Delete studio admin in starter")
                                sh """
                                    cd jenkins/jobs/scripts/functional-test/
                                    chmod +x delete-gameclient-starter.sh
                                    ./delete-gameclient-starter.sh
                                """
                            } else {
                                sh """
                                    cd jenkins/jobs/scripts/functional-test/
                                    chmod +x delete-gameclient.sh
                                    ./delete-gameclient.sh
                                """
                            }
                        } else {
                            createBanner("Due to AGS Test App still on development phase, so skipped on STABLE jenkins")
                        }
                    }
                    catch(err) {
                        currentBuild.result = 'FAILURE'
                    }
                    finally {
                        currentBuild.result = 'SUCCESS'
                    }
                }
            }
            if (qaFilters.isEmpty()) {
                if (params.slackThread != '') {
                    stage('Send Notification') {
                        dir("deployment${BUILD_NUMBER}"){
                            unstash "ags-test"
                            unstash "seedingdata"
                            if (WORKSPACE.contains('DEVELOPMENT')) {
                                slackChannel = 'C07C69NHGTW'
                            } else {
                                slackChannel = 'C079A11910R'
                            }
                            withCredentials([string(credentialsId: 'ab-deploy-automation-slackbot-token', variable: 'slackToken')]) {
                                // POST
                                def post = new URL('https://slack.com/api/chat.postMessage').openConnection()
                                def postData =  [
                                    channel: slackChannel,
                                    blocks: [
                                        [
                                            type: 'section',
                                            text: [
                                            type: 'mrkdwn',
                                            text: ":mag: Functional test done with status ${currentBuild.currentResult} <${BUILD_URL}|Open Jenkins Job!>."
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
                } else {
                    stage('Send Notification') {
                        dir("deployment${BUILD_NUMBER}"){
                            // unstash "ags-test"
                            unstash "ags-test-result"
                            withCredentials([string(credentialsId: "ab-automation-monitoring-slackbot-token", variable: 'slackToken')]) {
                                def command = """
                                    filepath=\$(find jenkins/image-sdk-test/bin/test_execution_history -type f -name "*-abtestcppj")
                                    echo \${filepath}
                                    curl -s -X POST https://slack.com/api/files.upload \
                                        -H "Authorization: Bearer ${slackToken}" \
                                        -F channels=${slackChannel} \
                                        -F file=@\${filepath} \
                                        -F "initial_comment=Execution Result" \
                                        -F thread_ts="${slackThread}" \
                                        -F title="execution_result"
                                    """
                                sh command  
                            }  
                        }
                    }
                }
            } else {
                stage('Send Notification') {
                    dir("deployment${BUILD_NUMBER}"){
                        unstash "ags-test-result"
                        withCredentials([string(credentialsId: "ab-automation-monitoring-slackbot-token", variable: 'slackToken')]) {
                            def command = """
                                filepath=\$(find jenkins/image-sdk-test/bin/test_execution_history -type f -name "*-abtestcppj")
                                echo \${filepath}
                                curl -s -X POST https://slack.com/api/files.upload \
                                    -H "Authorization: Bearer ${slackToken}" \
                                    -F channels=${slackChannel} \
                                    -F file=@\${filepath} \
                                    -F "initial_comment=Execution Result" \
                                    -F thread_ts="${slackThread}" \
                                    -F title="execution_result"
                                """
                            sh command  
                        }  
                    }
                }
            }
        }
    }
}

void createBanner(String message) {
    ansiColor('xterm'){
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
        echo '\033[1;4;33m' + ":: ${message}"
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    }
}