import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def createPR(String branchName, String commitMessage, String prSummary) {
  withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
    def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
    def postData =  [
      title: "${commitMessage}",
      source: [
        branch: [
          name: "${branchName}"
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
    if(postRC.equals(200) || postRC.equals(201)) {
      def jsonSlurper = new JsonSlurper()
      def reply = post.getInputStream().getText()
      def replyMap = jsonSlurper.parseText(reply)
      def prUrl = replyMap?.links?.html?.href
      def prId = replyMap?.id
      
      println("===:: PR has been Created âœ… ::===")
      println("PR URL: ${prUrl}")
      println("PR ID: ${prId}")
      return[url: prUrl, id: prId]
    }
  }
}

return this
