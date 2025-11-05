import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String[] modifiedDirs
def prIdentifier = "autorightsizing"
def identifier
def buildStopped = false

node('infra-sizing') {
  container('tool') {
    stage('Check Params'){
      if (WORKSPACE.contains("DEVELOPMENT")) {
        prIdentifier = "DEVELOPMENT" + prIdentifier
      }
      echo payload
      identifier = sh(returnStdout: true, script: '''
          echo "${branchName}" | awk -F'-' '{print $1}'
        '''
      ).trim()
      if (identifier != prIdentifier) {
        currentBuild.result = 'ABORTED'
        buildStopped = true
        currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
        error('Not autorightsizing changes - Aborting the build.')
      }
      if (mergeDestinationBranch != "master") {
        currentBuild.result = 'ABORTED'
        buildStopped = true
        currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
        error('Not master merge changes - Aborting the build.')
      }
      if (prState == "MERGED") {
        currentBuild.result = 'ABORTED'
        buildStopped = true
        currentBuild.displayName = "#[Skipped] - ${branchName} - ${BUILD_NUMBER}"
        error('This is a merge event - Aborting the build.')
      }
    }
    if (!buildStopped) {
      currentBuild.displayName = "#${branchName} - ${BUILD_NUMBER}"
      stage('Get Modified Folder'){
        withCredentials([string(credentialsId: "internal-deploy-tool-token-0", variable: 'bbAccessToken')]) {
          def cmd = '''
            # get latest commit from master
            latestMasterCommitHash="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/deployments/commits/master?pagelen=1" | jq -r '.values[0].hash')"

            # changes traversal
            DIFF="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/deployments/diff/${changesCommitHash}..${latestMasterCommitHash}" |
                grep -E '^\\+\\+\\+ b/' |
                cut -d ' ' -f2- |
                cut -c3- |
                grep services-overlay |
                grep -v utils |
                awk -F '/' '{print \$1 "/" \$2 "/" \$3 "/" \$4 "/" \$5}' |
                sort -u)"
            echo ${DIFF}
          '''
          diff = sh(returnStdout: true, script: cmd).trim()
          modifiedDirs = diff.split(' ').collect{it.trim()}.findAll{it}
        }
      }
      stage('Dispatch'){
        modifiedDirs.each { dir ->
          try {
            echo "Triggering k8s manifest validation job for modified folder: $dir"
            build job: 'Services-Overlay-Manifest-Validation',
            parameters: [
              [$class: 'StringParameterValue', name: 'branchName', value: branchName],
              [$class: 'StringParameterValue', name: 'dir', value: dir],
              [$class: 'StringParameterValue', name: 'commitHash', value: changesCommitHash],
            ],
            wait: false
          } catch (Exception e) {
            echo "Caught an exception: ${e.message}"
            throw e
          }
        }
      }
    }
  }
}
