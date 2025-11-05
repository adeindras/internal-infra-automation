import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def createPR(String maintenanceTitle, String targetEnvironmentName, String bitbucketBranchName) {
  prSummary="""
    :: ${maintenanceTitle} ${targetEnvironmentName} \n \n
    """
  withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
    def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
    def postData =  [
      title: "feat: ${maintenanceTitle} ${targetEnvironmentName}",
      source: [
        branch: [
          name: "${bitbucketBranchName}"
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
      prHtmlLink = replyMap.links.html.href
    }
  }
}

return this
