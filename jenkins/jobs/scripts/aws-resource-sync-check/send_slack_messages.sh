#!/bin/bash

## Local Variables
# NODE_ENVIRONMENT_NAME=""
# DURATION_SECONDS=""
# DURATION_MINUTES=""
# DURATION_HOURS=""
# EXECUTION_LINK=""
# CHANNEL_ID=""
# SLACK_TOKEN=""

## Pipeline Variables
WORKDIR="$PWD"
OUTPUT_REPORT_DIR="$WORKDIR/output/iacinfo"
OUTPUT_FILE_DIRS="$WORKDIR/output/directory_lists"
PATTERN_DRIFT="*_report"
infra_count=0
infra_drift_count=0
infra_check_error_count=0

response=$(curl -s -X POST https://slack.com/api/chat.postMessage \
  -H "Authorization: Bearer $SLACK_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "{
        \"channel\": \"${CHANNEL_ID}\",
        \"blocks\": [
            {
              \"type\": \"section\",
              \"text\": {
                \"type\": \"mrkdwn\",
                \"text\": \"Your infrastucture drift check report:\"
              }
            },
            {
              \"type\": \"section\",
              \"fields\": [
                {
                  \"type\": \"mrkdwn\",
                  \"text\": \"*Env:*\n${NODE_ENVIRONMENT_NAME}\"
                }
              ]
            },
            {
              \"type\": \"actions\",
              \"elements\": [
                {
                  \"type\": \"button\",
                  \"text\": {
                    \"type\": \"plain_text\",
                    \"text\": \":rundeck: Rundeck Execution Link\",
                    \"emoji\": true
                  },
                  \"value\": \"rundeck_execution_link\",
                  \"url\": \"${EXECUTION_LINK}\"
                }
              ]
            }
          ]
      }")

thread_ts=$(echo "$response" | jq -r '.ts')
if [ "$(echo "$response" | jq -r '.ok')" != "true" ]; then
  echo "Failed to send message: $response"
  exit 1
fi

# Function to upload a file to Slack
upload_to_slack() {
  local file_path="$1"
  local messages="$2"

  if [[ -n "$file_path" ]]; then
    local file_name=$(basename "$file_path")
    curl -s -X POST https://slack.com/api/files.upload \
      -H "Authorization: Bearer $SLACK_TOKEN" \
      -F "channels=$CHANNEL_ID" \
      -F "file=@$file_path" \
      -F "initial_comment=:large_orange_square: Drift report for ${file_name}" \
      -F "thread_ts=$thread_ts" > /dev/null
  fi

  if [[ -n "$messages" ]]; then
    upload_message_to_slack "$messages"
  fi
  
}

upload_message_to_slack() {
  curl -s -X POST https://slack.com/api/chat.postMessage \
    -H "Authorization: Bearer $SLACK_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
          \"channel\": \"$CHANNEL_ID\",
          \"text\": \"$1\",
          \"thread_ts\": \"$thread_ts\"
        }"
}

check_infra_status() {
  local tfplan_file="$1.tfplan"
  local drift_report="$OUTPUT_REPORT_DIR/$1_drift_report"
  local changes_report="$OUTPUT_REPORT_DIR/$1_changes_report"

  local tfplan_exists=0
  local drift_exists=0
  local changes_exists=0

  [[ -f "$OUTPUT_REPORT_DIR/$tfplan_file" ]] && tfplan_exists=1
  [[ -f "$drift_report" ]] && drift_exists=1
  [[ -f "$changes_report" ]] && changes_exists=1

  local drift_size=0
  local changes_size=0
  

  [[ $drift_exists -eq 1 ]] && drift_size=$(stat --printf="%s" "$drift_report")
  [[ $changes_exists -eq 1 ]] && changes_size=$(stat --printf="%s" "$changes_report")

  if [[ $tfplan_exists -eq 1 && $drift_exists -eq 1 && $changes_exists -eq 1 ]]; then
    infra_count=$((infra_count+1))
    if [[ $drift_size -eq 0 && $changes_size -eq 0 ]]; then
      echo ":icons-ok:No infra drift detected: $1"
      upload_message_to_slack ":icons-ok:No infra drift detected: $1"
    else
      echo "Infra drift detected: $1"
      upload_to_slack "$drift_report" ":icons-warning:Infra drift detected!: $1"
      upload_to_slack "$changes_report"
      infra_drift_count=$((infra_drift_count+1))
    fi
  elif [[ $tfplan_exists -eq 0 && $drift_exists -eq 1 && $changes_exists -eq 1 ]]; then
    if [[ $drift_size -eq 0 && $changes_size -eq 0 ]]; then
      echo ":no_entry:Terragrunt plan operation check failed: $1"
      upload_message_to_slack ":no_entry:Terragrunt plan operation check failed: $1"
      infra_check_error_count=$((infra_check_error_count+1))
    fi
  fi

  response=$(curl -s -X POST https://slack.com/api/chat.update \
  -H "Authorization: Bearer $SLACK_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -d "{
        \"channel\": \"${CHANNEL_ID}\",
        \"ts\": \"$thread_ts\",
        \"blocks\": [
            {
              \"type\": \"section\",
              \"text\": {
                \"type\": \"mrkdwn\",
                \"text\": \"Your infrastucture drift check report:\"
              }
            },
            {
              \"type\": \"section\",
              \"fields\": [
                {
                  \"type\": \"mrkdwn\",
                  \"text\": \"*Env:*\n${NODE_ENVIRONMENT_NAME}\"
                },
                {
                  \"type\": \"mrkdwn\",
                  \"text\": \"*Result:*\n${infra_drift_count}/${infra_count} is drifting\"
                }
                ,
                {
                  \"type\": \"mrkdwn\",
                  \"text\": \"*Infra Check error:*\n${infra_check_error_count} times\"
                }
              ]
            },
            {
              \"type\": \"actions\",
              \"elements\": [
                {
                  \"type\": \"button\",
                  \"text\": {
                    \"type\": \"plain_text\",
                    \"text\": \":rundeck: Rundeck Execution Link\",
                    \"emoji\": true
                  },
                  \"value\": \"rundeck_execution_link\",
                  \"url\": \"${EXECUTION_LINK}\"
                }
              ]
            }
          ]
      }")
  if [ "$(echo "$response" | jq -r '.ok')" != "true" ]; then
    echo "Failed to update message: $response"
    exit 1
  fi
}

while IFS= read -r line; do
  transformed=$(dirname "$line" | awk -F'/' '{print $(NF-1)"/"$NF}' | sed 's|/|_|g')
  check_infra_status "$transformed"
done < "$OUTPUT_FILE_DIRS"