import groovy.json.JsonOutput
import groovy.json.JsonSlurper

void createPullRequests(String repoUrl, String branch, String commitMessage, String summary) {
  withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
    // POST
    def post = new URL(repoUrl).openConnection();
    def postData =  [
        title: commitMessage,
        source: [
            branch: [
                name: branch
            ]
        ],
        reviewers:[],
        destination: [
            branch: [
                name: "master"
            ]
        ],
        summary: [
            raw: summary
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
    println "HTTP Response Code: ${postRC}"
    if (![200, 201].contains(postRC)) {
        def responseText
        responseText = post.errorStream?.text ?: "<no response body>"
        println "Response Body:\n${responseText}"
    }

    if(postRC.equals(200) || postRC.equals(201)) {
        def jsonSlurper = new JsonSlurper()
        def reply = post.getInputStream().getText()
        def replyMap = jsonSlurper.parseText(reply)
        prHtmlLink = replyMap.links.html.href
        def GREEN = "\u001B[32m"
        def RESET = "\u001B[0m"
        println "\n" + "="*80
        println "${GREEN}PR Link: ${prHtmlLink} ${RESET}"
        println "="*80 + "\n"
    }
  }
}

void pushChanges(String branch, String commitMessage, String pathspec){

  sshagent(['bitbucket-repo-read-only']) {

    sh """#!/bin/bash
        set -e
        export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

        git checkout -b "$branch"
        git config --global user.email "build@accelbyte.net"
        git config --global user.name "Build AccelByte"
        git status --short
        git add "$pathspec"
        git commit -m "$commitMessage" || true
        git push --set-upstream origin "$branch"
    """
  }
}