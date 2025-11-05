import groovy.json.JsonOutput
import groovy.json.JsonSlurper

properties(
  [
	parameters([
	  string(defaultValue: '', name: 'targetEnvironmentName', description: 'please follow the naming of eks cluster (i.e sandbox-justice-dev)'),
	  string(defaultValue: '', name: 'maintenanceActivity', description: 'please explain what kind of activity (i.e Rollout MSK, EKS Upgrade, etc)'),
	  string(defaultValue: '', name: 'maintenanceStart', description: 'please see the start time on the <a href="https://docs.google.com/spreadsheets/d/1NPdoI8_128rzOmUYtcyT7I4jpY47gE7viFboDntDbKo/edit?gid=1693698381#gid=1693698381" target="_blank"> maintenance calendar </a> <br>Then run <b>date -u +"%Y-%m-%dT%H:%M" -d "+7 hours"</b>'),
	  string(defaultValue: '', name: 'maintenanceEnd', description: 'please see on the sheet start/end maintenance and go to coulmn duration on the <a href="https://docs.google.com/spreadsheets/d/1c_ID_dg_Tb1cLuY9j6Pg3ept7Upq8jqqbfl280j0EpU/edit?gid=823297743#gid=823297743" target="_blank">  customer email maintenance activity </a> <br>Then run <b>date -u +"%Y-%m-%dT%H:%M" -d "+7 hours +1 hours"</b>'),
	])
  ]
)

String targetEnvironmentName = params.targetEnvironmentName
String maintenanceActivity = params.maintenanceActivity
String maintenanceStart = params.maintenanceStart
String maintenanceEnd = params.maintenanceEnd
String slackGroupHosting = "S044ZF5HPFF"
String slackGroupLiveOps = "S01SPJ0130U"
String slackGroupQaInfra = "S067JL6JT2T"
String slackChannelTest = "C04NV2TKJD6" //#test-channel-for-me
String slackChannelFunctionalLiveops = "C05A2HH0R6Z" // #functional-liveops
String slackChannelLSMAlertOperations = "C071Q9F729K" // #lsm-alert-operations
String slackChannelReportInfraChanges = "C017L2M1C3D" // #report-infra-changes
String slackChannelAlertSilenced = "C076P1NBDFU" // #alert-silenced
String tempDir="temp$BUILD_NUMBER"
String tz = "+07:00" // WIB
currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-${maintenanceActivity}"

node('infra-sizing') {
	container('tool') {
		dir(tempDir){
			withCredentials([
				string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken'),
				string(credentialsId: "rundeck-prod-devportal-api-token", variable: 'rundeckToken'),
			]) {
				stage('Silenced Alert') {
					startsAt = sh( returnStdout: true, script: "echo ${maintenanceStart}:00${tz}").trim()
					endsAt = sh( returnStdout: true, script: "echo ${maintenanceEnd}:00${tz}").trim()
					switch ("") {
						case startsAt:
							currentBuild.result = 'FAILURE'
							exit 1
							break
						case endsAt:
							currentBuild.result = 'FAILURE'
							exit 1
							break
						default:
							echo "startsAt: ${startsAt}"
							echo "endsAt: ${endsAt}"
							break
					}

					def post = new URL("https://rundeck.prod.devportal.accelbyte.io/api/45/job/8a4a3d88-2cc3-4646-a0e3-914a5ac893aa/run").openConnection();
					def postData = [
						"options": [
							"environment_name": "${targetEnvironmentName}",
							"startime": "${startsAt}",
							"endtime": "${endsAt}"
						]
					]
					def jsonPayload = JsonOutput.toJson(postData)
					post.setRequestMethod("POST")
					post.setDoOutput(true)
					post.setRequestProperty("Accept", "application/json")
					post.setRequestProperty("Content-Type", "application/json")
					post.setRequestProperty("X-Rundeck-Auth-Token", "${rundeckToken}")
					post.getOutputStream().write(jsonPayload.getBytes("UTF-8"))
					def postRC = post.getResponseCode();
					println(postRC);
					if (postRC.equals(200) || postRC.equals(201)) {
						def jsonSlurper = new JsonSlurper()
						def reply = post.getInputStream().getText()
						def replyMap = jsonSlurper.parseText(reply)
						println(replyMap);
					} else {
						currentBuild.result = 'FAILURE'
						exit 1
					}
				}
			}

			stage('Send to #functional-liveops'){
				withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
					def post = new URL("https://slack.com/api/chat.postMessage").openConnection();
					def postData =  [
						channel: "${slackChannelLSMAlertOperations}",
						blocks: [
							[
								type: "section",
								text: [
									type: "mrkdwn",
									text: ":robot_face: [${targetEnvironmentName}] Hosting Platform Bot :robot_face:"
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Desc:*\n Hi <!subteam^${slackGroupLiveOps}> \n<!subteam^${slackGroupHosting}> silence the alert due the following activity"
									],
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Activity:*\n ${maintenanceActivity}"
									]
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Environment:*\n ${targetEnvironmentName}"
									],
								]
							],
							[
								type: "section",
								fields: [
									[
										type: "mrkdwn",
										text: "*Start:*\n ${startsAt}"
									],
									[
										type: "mrkdwn",
										text: "*End:*\n ${endsAt}"
									],
								]
							],
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
				}
			}
		}
	}
}