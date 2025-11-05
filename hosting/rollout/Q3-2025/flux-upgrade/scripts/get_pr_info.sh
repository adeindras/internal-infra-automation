#!/bin/bash
set -e

AUTH_HEADER="$1"
PR_ID="$2"


if [ -z "$AUTH_HEADER" ] || [ -z "$PR_ID" ]; then
  echo "Usage: $0 <base64_auth> <pr_id>"
  exit 1
fi

while true; do
  PR_STATUS=$(curl -s -u ${AUTH_HEADER} \
    "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/pullrequests/${PR_ID}" \
    | jq -r '.state')

  echo "Current PR Status: $PR_STATUS"

  if [ "$PR_STATUS" = "MERGED" ]; then
    echo "✅ PR is no longer open, PR is $PR_STATUS!"
    break
  elif [ "$PR_STATUS" = "DECLINED" ]; then
    echo "❌ PR is no longer open, PR is $PR_STATUS!"
    exit 1
  fi

  echo "🕒 PR is still open. Retrying in 1 minute..."
  sleep 5s
done
