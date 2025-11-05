import groovy.json.JsonOutput
import groovy.json.JsonSlurper

envList = getEnvironmentList()
properties(
  [
    parameters([
      choice(choices: envList, name: "targetEnvironmentName", description: "Environment to migrate"),
      string(defaultValue: '', name: 'ksServicePath', description: "Kustomization service path from relative folder sync (e.g extended/falco, core/alb-controller)"),
      string(defaultValue: '', name: 'ksServiceVersion', description: "Kustomization service version (e.g v4.14.2, 3.6.1)"),
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
        envData.push("Error getting environment list data")
    } else {
        return envData
    }
}

def compareVersion(String v1, String v2) {
    def normalize = { String version ->
        version = version.trim()
        def base = version.split('-')[0]
        def suffix = version.contains('-') ? version.split('-')[1] : ''
        def parts = base.tokenize('.').collect { it.toInteger() }
        while (parts.size() < 3) parts << 0
        return [parts, suffix]
    }

    def (parts1, suffix1) = normalize(v1)
    def (parts2, suffix2) = normalize(v2)

    for (int i = 0; i < 3; i++) {
        if (parts1[i] != parts2[i]) {
            return parts1[i] < parts2[i] ? -1 : 1
        }
    }

    if (suffix1 == suffix2) return 0
    if (!suffix1) return 1
    if (!suffix2) return -1

    return suffix1 <=> suffix2
}

def vRemover(version) {
    return version?.replaceFirst(/^v/, '') ?: ''
}

def reconcileKs(manifestKs) {
    def retryTime = [30, 60, 90, 120, 240]
    for (int i=0; i < retryTime.size(); i++) {
      def time=retryTime[i]
      echo "Attempt ${i+1}/${retryTime.size()} reconciling kustomization ${manifestKs}"
      sh "flux -n flux-system reconcile ks ${manifestKs} --with-source"

      sleep(time)
      manifestStatus = sh(returnStdout: true, script: """
          kubectl -n flux-system get ks ${manifestKs} -oyaml 2> /dev/null | yq '.status.conditions[0].type // "NotReady"'
      """).trim()

      if (manifestStatus == "Ready") {
        echo "Manifest kustomization ${manifestKs} is 'Ready' after ${i+1} attempts"
        break
      } else if ( i == retryTime.size() - 1) {
        echo "Kustomization failed to reach 'Ready' after ${retryTime.size()} attempts"
      } else {
        echo "Continue retrying next internval ..."
      }
    }
}

def getServiceVersion(serviceName) {
    manifestPath = sh(returnStdout: true, script: """
      kubectl -n flux-system get ks -o yaml | yq -r '.items[] | select(.spec.path | contains("manifests/platform/${serviceName}")) | .spec.path'
    """).trim()

    if (manifestPath.contains("/v")) {
      def currentServiceVersionRaw = (manifestPath =~ /v?\d+\.\d+\.\d+(?:-\d+)?/)[0]
      return currentServiceVersion = vRemover(currentServiceVersionRaw)
    } else {
      return currentServiceVersion = (manifestPath =~ /\b\d+\.\d+\.\d+(?:-[\w\d]+)?\b/)[0]
    }
}          

String targetEnvironmentName = params.targetEnvironmentName
String ksServicePath = params.ksServicePath
String ksServiceVersion = params.ksServiceVersion
String envDirectory
String environmentDir
String awsAccountId
String awsRegion
String timeStamp=currentBuild.startTimeInMillis 
String tempDir="temp$BUILD_NUMBER"
String syncDirectory
Boolean manifestInDirectory
String BB_BRANCH_NAME
Boolean ksServiceOutdated
Boolean ksVersionEqual = false
String currentServiceVersion

def targetServiceVersion = vRemover(ksServiceVersion)
def (directoryName, serviceName) = ksServicePath.split('/')

def (customer, project, environment) = targetEnvironmentName.split('-')
def userId = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')[0]['userId']

BB_BRANCH_NAME = "patch-kustomization-${serviceName}@${targetServiceVersion}-${targetEnvironmentName}-${timeStamp}"
currentBuild.displayName = "#${BUILD_NUMBER} -patch- ${serviceName}@${targetServiceVersion} - ${targetEnvironmentName}"

node('hosting-agent') {
  container('tool') {
    dir(tempDir){
      stage('Clone IAC Repository') {
        sshagent(['bitbucket-repo-read-only']) {
          sh """#!/bin/bash
            set -e
            export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
            git clone --quiet "git@bitbucket.org:accelbyte/iac.git" || true
            rm -rf iacTemp || true
            cp -R iac iacTemp || true
            chmod -R 777 iacTemp || true
            rm -rf ~/.aws/config || true
          """
        }
      }
      
      stage('Set AWS Credentials'){
        dir('iacTemp') {
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
  
          awsAccessMerged = sh(returnStdout: true, script: """
            set +x
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
          """
          ).trim()

          def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = awsAccessMerged.split(':')
          env.AWS_ACCESS_KEY_ID = awsAccessKeyId
          env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey
          env.AWS_SESSION_TOKEN = awsSessionToken
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

      stage('Prepare Common Variable') {
        syncDirectory = "manifests/clusters/${customer}/${project}/${awsRegion}/${environment}/sync/"
        
        dir("iac") {
          dir("${syncDirectory}") {
            if(fileExists("${directoryName}/${serviceName}.yaml")) {
              manifestInDirectory = true
            } else {
              manifestInDirectory = false
              error "file not found"
            }
          }
        }

        if (manifestInDirectory == true) {
          currentServiceVersion = getServiceVersion(serviceName)

          echo "Current service version: ${currentServiceVersion}"
          echo "Target service version: ${targetServiceVersion}"

          def compareResult = compareVersion(targetServiceVersion, currentServiceVersion)
          echo "compareResult = ${compareResult}"

          if (compareResult < 0) {
              echo "Current Service version is outdated (${currentServiceVersion})"
              ksServiceOutdated = true
          } else if (compareResult > 0 ) {
              echo "Service version is up to date (${currentServiceVersion})"
              ksServiceOutdated = false
          } else {
              echo "Versions are equal (${currentServiceVersion})"
              ksVersionEqual = true
          }

          targetManifestPath = manifestPath.replace(currentServiceVersion, targetServiceVersion)
          echo "Target manifest path: ${targetManifestPath}"
          dir("iac") {
            if(fileExists(targetManifestPath)) {
              echo "Manifest path (${targetManifestPath}) is available"
            } else {
              error "Manifest path (${targetManifestPath}) is not available"
            }
          }
        } else {
          error "Service manifest not found, skipping version check."
        }
      }

      stage('Patch Manifest Version'){
        if (ksServiceOutdated == true) {
          def userInput = input(
              id: 'userInput', 
              message: "The current version is up-to-date (${currentServiceVersion}), are you sure to patch to old version(${targetServiceVersion})", 
              parameters: [
                  [$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
              ]
          )

          if (!userInput) {
              error "Build failed: User did not confirm."
          }
        }

        if (!ksVersionEqual) {
          dir("iac") {
            sh """
              cd ${syncDirectory}
              sed -i 's|${currentServiceVersion}|${targetServiceVersion}|g' ${directoryName}/${serviceName}.yaml
            """
          }
        } else {
          echo "Skip patching version are up-to-date"
        }
      }

      stage('Create Branch and Commit'){
        if (!ksVersionEqual) {
          dir("iac") {
            sshagent(['bitbucket-repo-read-only']) {
              sh """#!/bin/bash
                set -e
                export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
                git checkout -b ${BB_BRANCH_NAME}
                
                git config --global user.email "build@accelbyte.net"
                git config --global user.name "Build AccelByte"
                chmod -R 644 ${syncDirectory} || true
                git add ${syncDirectory}
                git commit -m "feat: patch kustomize ${serviceName} to ${targetServiceVersion}"
                git push --set-upstream origin ${BB_BRANCH_NAME}
              """
            }
          }
        } else {
          echo "Skip patching version are up-to-date"
        }
      }

      stage("Create PR IAC Repository") {
        if (!ksVersionEqual) {
          prSummary="""
:: Patch ${serviceName} version to ${targetServiceVersion} ${targetEnvironmentName} \n \n
:: PR Created by ${userId} \n \n
          """
          withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
            def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
            def postData =  [
              title: "patch: ${serviceName} version to ${targetServiceVersion} ${targetEnvironmentName}",
              source: [
                branch: [
                  name: "${BB_BRANCH_NAME}"
                ]
              ],
              reviewers: [
                [
                  uuid: "{6cee0dcd-3d6e-4ef1-8cd0-4ca217ed32d2}" // Adin Baskoro Pratomo
                ],
                [
                  uuid: "{f115f536-48bf-42f0-9634-30f53f03ed13}" // Adi Purnomo
                ],
                [
                  uuid: "{8f4086ef-41e9-4eb3-80c0-84212c1c7594}" // Fahmi Maghrizal Mochtar
                ],
                [
                  uuid: "{3bc5a80a-bb63-40a5-829c-82cbde04c2a3}" // Radian Satria Panigas
                ],
                [
                  uuid: "{b0089c2d-a507-4211-bc6f-e23cd1735f7a}" // Muhamad Ar Ghifary
                ],
                [
                  uuid: "{4c57253e-0370-446c-8824-ee350e24b4df}" // Robbie Zhang
                ],
                [
                  uuid: "{a60f808f-4034-49da-89f3-4daf9a2367b6}" // Husni Bakri
                ],
                [
                  uuid: "{c2927dd0-de16-4f0a-a1cb-1c3a7e73b4ef}" // Ade Saputra
                ],
                [
                  uuid: "{92ee2cd7-8ca6-472f-bba8-2b2d7008867c}" // Wandiatama Wijaya Rahman
                ]
              ],
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
            post.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
            post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
            def postRC = post.getResponseCode();
            println(postRC);
            if(postRC.equals(200) || postRC.equals(201)) {
              def jsonSlurper = new JsonSlurper()
              def reply = post.getInputStream().getText()
              def replyMap = jsonSlurper.parseText(reply)
              prHtmlLink = replyMap.links.html.href
              println(replyMap);
            }
          }
        } else {
          echo "Skip creating PR"
        }
      }

      stage("Reconcile KS") {
        if (!ksVersionEqual) {
          def userInputKustomization = input(
            id: 'userInput', 
            message: 'Please merge the PR first before continue reconcile', 
            parameters: [
              [$class: 'BooleanParameterDefinition', defaultValue: true, description: '', name: 'Are you sure to proceed']
            ]
          )

          if(!userInputKustomization) {
            error "failed to reconcile"
          }

          if (serviceName.contains("redis") && currentServiceVersion.contains("v17")) {
            sh """
              kubectl -n redis exec -t redis-master-0 -- redis-cli SAVE
            """
          }

          sh """
            wget -qO- https://github.com/fluxcd/flux2/releases/download/v0.37.0/flux_0.37.0_linux_amd64.tar.gz | tar -xz
            chmod +x flux
            mv flux /bin/flux
            which flux
            flux version --client
          """

          reconcileKs("flux-system")
          reconcileKs("${serviceName}")

          currentServiceVersion = getServiceVersion(serviceName)
          
          if (currentServiceVersion == targetServiceVersion) {
            echo "${serviceName} version are updated"
          } else {
            echo "${serviceName} not updated (${currentServiceVersion}) expected version ${targetServiceVersion}. Please double check in the cluster"
          }
        } else {
          echo "Skip reconcile service version is up-to-date"
        }
      }
    }
  }
}