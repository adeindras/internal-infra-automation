import logging
import os
# Import WebClient from Python SDK (github.com/slackapi/python-slack-sdk)
from slack_sdk import WebClient
from slack_sdk.errors import SlackApiError

cluster_name = os.environ.get('CLUSTER_NAME', 'sandbox-justice-dev')

pipeline_env = os.environ.get('PIPELINE_ENV', 'STABLE')

build_url = os.environ.get('BUILD_URL', 'https://jenkinscd.accelbyte.io')
user_id = os.environ.get('JOB_USER_ID', 'unknown')


client = WebClient(token=os.environ.get("SLACK_BOT_TOKEN"))
logger = logging.getLogger(__name__)

# ID of the channel you want to send the message to
if pipeline_env == 'DEVELOPMENT':
    channel_id = "C07UY55SE20"
else:
    channel_id = "C080SRE92NA"

# Block format

block_progress = [
                    {"type": "section", "text": {"type": "mrkdwn", "text": ":timer_clock: *DB Infra Readiness Check *\n*{}*".format(cluster_name)}}, 
                    {"type": "section", "fields": 
                        [
                            {"type": "mrkdwn", "text": "*Environment name:*\ncustomer-project-env"}, 
                            {"type": "mrkdwn", "text": "*Requester:*\nYour name here"}, {"type": "mrkdwn", "text": "*Pipeline link:*\n<{}|Jenkins>".format(build_url)}
                        ]
                    }
] 

block_failed = [
                    {"type": "section", "text": {"type": "mrkdwn", "text": ":x: *DB Infra Readiness Check *\n*{}*".format(cluster_name)}}, 
                    {"type": "section", "fields": 
                        [
                            {"type": "mrkdwn", "text": "*Environment name:*\ncustomer-project-env"}, 
                            {"type": "mrkdwn", "text": "*Requester:*\nYour name here"}, {"type": "mrkdwn", "text": "*Pipeline link:*\n<{}|Jenkins>".format(build_url)}
                        ]
                    }
] 

def send_slack_notification(cluster_name, spreadsheet_link, status=True):
    block_success = [
                        {"type": "section", "text": {"type": "mrkdwn", "text": ":white_check_mark: *DB Infra Readiness Check *\n*{}*".format(cluster_name)}}, 
                        {"type": "section", "fields": 
                            [
                                {"type": "mrkdwn", "text": "*Environment name:*\n{}".format(cluster_name)}, 
                                {"type": "mrkdwn", "text": "*Requester:*\n{}".format(user_id)}, {"type": "mrkdwn", "text": "*Pipeline link:*\n<{}|Jenkins>".format(build_url)}, 
                                {"type": "mrkdwn", "text": "*Output:*\n<{}|Link to Google Sheets>".format(spreadsheet_link)}
                            ]
                        }
    ]

    if status is True:
        block = block_success
    else:
        block = block_failed

    try:
        # Call the chat.postMessage method using the WebClient
        result = client.chat_postMessage(
            channel=channel_id, 
            blocks=block
            # text="DB Infra Readiness Check has been triggered and finished. The result has been exported here: {}".format(spreadsheet_link)
        )
        # logger.info(result)
        return result

    except SlackApiError as e:
        logger.error(f"Error posting message: {e}")
        return None

# Kept for future use, experimental feature as of now
def send_slack_message(cluster_name, spreadsheet_link):
    try:
        # Call the chat.postMessage method using the WebClient
        result = client.chat_postMessage(
            channel=channel_id, 
            blocks=block_progress
        )
        # logger.info(result)
        return result

    except SlackApiError as e:
        logger.error(f"Error posting message: {e}")
        return None

def update_slack_message(ts, spreadsheet_link, status=True):
    block_success = [{"type": "section", 
            "text": {"type": "mrkdwn", "text": ":white_check_mark: *DB Infra Readiness Check *\n*{}*".format(cluster_name)}}, 
            {"type": "section", "fields": 
                [
                    {"type": "mrkdwn", "text": "*Environment name:*\n{}".format(cluster_name)}, 
                    {"type": "mrkdwn", "text": "*Requester:*\nYour name here"}, {"type": "mrkdwn", "text": "*Pipeline link:*\n<{}|Jenkins}>".format(build_url)}, 
                    {"type": "mrkdwn", "text": "*Output:*\n<{}|Link to Google Sheets>".format(spreadsheet_link)}]}
    ]
    
    if status is True:
        block = block_success
    else:
        block = block_failed

    try:
        # Call the chat.postMessage method using the WebClient
        result = client.chat_postMessage(
            channel=channel_id, 
            blocks=block,
        )
        # logger.info(result)
        return result

    except SlackApiError as e:
        logger.error(f"Error posting message: {e}")
        return None


