import hudson.plugins.git.extensions.impl.SparseCheckoutPath
import jenkins.plugins.http_request.ResponseContentSupplier
import java.security.MessageDigest

properties(
  [
    parameters([
      string(defaultValue: '', name: 'commitHash'),
      string(defaultValue: '', name: 'dir'),
      string(defaultValue: '', name: 'branchName')
    ])
  ]
)

// constants
BITBUCKET_CREDS_ID = 'bitbucket-repo-read-only'
COMMIT_HASH = params.commitHash
DEPLOYMENT_REPO_SLUG = "deployments"
MODIFIED_FOLDER = params.dir
BRANCH = params.branchName
String SERVICE_NAME
String CLUSTER_NAME
String CLUSTER_PATH

node("deploy-agent") {
  container('tool') {
    try {
      stage('Init') {
        createBanner("STAGE: Initializing.. sending status to bitbucket")
        if(!MODIFIED_FOLDER?.trim()) {
          throw new SkipException('INFO: MODIFIED_FOLDER is missing, aborting pipeline')
        }
        CLUSTER_PATH = MODIFIED_FOLDER.split("/services-overlay/")[0]
        CLUSTER_NAME = CLUSTER_PATH.replace('/','-')
        SERVICE_NAME = MODIFIED_FOLDER.split("/services-overlay/")[1]
        currentBuild.displayName = "#${SERVICE_NAME} - ${CLUSTER_NAME} - ${BUILD_NUMBER}"
        withCredentials([string(credentialsId: 'internal-deploy-tool-token-0', variable: 'BITBUCKET_ACCESS_TOKEN')]) {
          updateBitbucketStatus(COMMIT_HASH, 'INPROGRESS', BITBUCKET_ACCESS_TOKEN, SERVICE_NAME, CLUSTER_NAME)
        }
      }
      stage('Checkout modified manifests') {
        createBanner("STAGE: Checkout modified manifests in $MODIFIED_FOLDER")
        dir("deployments${BUILD_NUMBER}") {
          withCredentials([
            string(credentialsId: 'internal-deploy-tool-token-0', variable: 'TOKEN_0'),
            string(credentialsId: 'internal-deploy-tool-token-1', variable: 'TOKEN_1'),
            string(credentialsId: 'internal-deploy-tool-token-2', variable: 'TOKEN_2'),
            string(credentialsId: 'internal-deploy-tool-token-3', variable: 'TOKEN_3')
          ]) {
            def buildNo = BUILD_NUMBER as int
            def mod = (buildNo % 4)
            String[] tokens = [TOKEN_0, TOKEN_1, TOKEN_2, TOKEN_3]
            env.BITBUCKET_ACCESS_TOKEN=tokens[mod]
            sh """
              bitbucket-downloader \
                -d $MODIFIED_FOLDER,$CLUSTER_PATH/services-overlay/utils \
                -r $COMMIT_HASH \
                -s $DEPLOYMENT_REPO_SLUG \
                -o \$(pwd)
            """
          }
        }
      }
      stage("Validate Manifest") {
        createBanner("STAGE: Validating manifest in $MODIFIED_FOLDER")
        dir("deployments${BUILD_NUMBER}/$MODIFIED_FOLDER") {
          if (!fileExists('kustomization.yaml')) {
            throw new SkipException('INFO: Kustomization file does not exist')
          } 
          sshagent([BITBUCKET_CREDS_ID]) {
            sh '''#!/bin/bash 
              set -e
              set -o pipefail
              export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
              mkdir -p ./manifests
              echo "\033[1;4;33m INFO: \033[0mGenerating the final kubernetes manifests"
              kubectl kustomize . --load-restrictor LoadRestrictionsNone | yq --split-exp '"./manifests/" + .kind + "-" + .metadata.name + ".yaml"' --no-doc
              ls ./manifests
              echo "\033[1;4;33m INFO: \033[0mValidating the final kubernetes manifests"
              kubeconform -ignore-missing-schemas -summary -verbose ./manifests
            '''
          }
        }
      }
    } catch (SkipException ex) {
      currentBuild.result = 'ABORTED'
      currentBuild.displayName = "${currentBuild.displayName} - [Skipped]"
      echo "Build is skipped:\n\t${ex.message}"
    } catch (InterruptedException err) {
      currentBuild.result = 'ABORTED'
    } catch (Exception err) {
      echo "Exception Thrown:\n\t${err}"
      currentBuild.result = 'FAILURE'
    } finally {
      stage('Notification') {
        withCredentials([string(credentialsId: 'internal-deploy-tool-token-0', variable: 'BITBUCKET_ACCESS_TOKEN')]) {
          updateBitbucketStatus(COMMIT_HASH, currentBuild.currentResult, BITBUCKET_ACCESS_TOKEN, SERVICE_NAME, CLUSTER_NAME)
        }
      }
    }
  }
}


void updateBitbucketStatus(String commitHash, status, token, serviceName, clusterName) {
  String bitbucketURL = "https://api.bitbucket.org/2.0/repositories"
  String bitbucketState

  // Map jenkins result to bitbucket state
  // https://developer.atlassian.com/cloud/bitbucket/rest/api-group-commit-statuses/
  String serviceKey = generateMD5(serviceName).take(20)
  switch(status) {
    case "SUCCESS":
      bitbucketState = "SUCCESSFUL"
      break
    case "INPROGRESS":
      bitbucketState = "INPROGRESS"
      break
    case "FAILURE":
      bitbucketState = "FAILED"
      break
    case "ABORTED":
      bitbucketState = "STOPPED"
      break
    default:
      throw new Exception("Unknown Jenkins build status: $status")
  }

  String requestBody = """
    {
      "key": "${serviceKey}",
      "name": "${serviceName}-${clusterName}",
      "state": "$bitbucketState",
      "description": "Jenkins CD Pipeline",
      "url": "${BUILD_URL}console"
    }
  """

  httpRequest(
    url: "${bitbucketURL}/accelbyte/${DEPLOYMENT_REPO_SLUG}/commit/${commitHash}/statuses/build",
    httpMode: "POST",
    requestBody: requestBody,
    contentType: "APPLICATION_JSON",
    customHeaders: [[name: 'Authorization', value: 'Bearer ' + token]],
  )
}

// Hacky way to skip later stages
public class SkipException extends Exception {
  public SkipException(String errorMessage) {
    super(errorMessage);
  }
}


void createBanner(String message) {
  ansiColor('xterm'){
    echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    echo '\033[1;4;33m' + ":: ${message}"
    echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
  }
}

String generateMD5(String s){
    MessageDigest.getInstance("MD5").digest(s.bytes).encodeHex().toString()
}