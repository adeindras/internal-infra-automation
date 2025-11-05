#!/bin/bash

# Source the .env file
source .env

# Role to assume in each subaccount
ASSUME_ROLE_NAME="OrganizationAccountAccessRole"
# Temporary directory to store credentials
TMP_DIR=$(mktemp -d)
# Output file
OUTPUT_FILE="list_of_missing_oidc_provider_check.csv"

OIDC_PROVIDER_NAME_TO_CHECK=${OIDC_PROVIDER_NAME}

if [ -z "${OIDC_PROVIDER_NAME_TO_CHECK}" ]; then
  echo "Variable OIDC_PROVIDER_NAME is empty"
  exit 1
fi

aws_access_key_id=${AWS_ACCESS_KEY_ID}
aws_secret_access_key=${AWS_SECRET_ACCESS_KEY}
aws_session_token=${AWS_SESSION_TOKEN}

if [ -z "$aws_session_token" ]; then
  echo "Variable aws_session_token is empty"
  exit 1
fi

restore_original_creds() {
  export AWS_ACCESS_KEY_ID="$aws_access_key_id"
  export AWS_SECRET_ACCESS_KEY="$aws_secret_access_key"
  export AWS_SESSION_TOKEN="$aws_session_token"
}

restore_original_creds

# Get list of all account IDs in the organization
account_ids=$(aws organizations list-accounts --query "Accounts[?Status=='ACTIVE'].Id" --output text)

# Loop through accounts
for account_id in $account_ids; do
  echo "Checking account: $account_id"

  restore_original_creds

  # Assume role
  creds=$(aws sts assume-role \
    --role-arn "arn:aws:iam::${account_id}:role/${ASSUME_ROLE_NAME}" \
    --role-session-name "missingOIDCProviderCheck" \
    --query "Credentials" \
    --output json 2>/dev/null)

  if [ -z "$creds" ]; then
    echo "Failed to assume role in account $account_id"
    continue
  fi

  # Set temporary credentials
  export AWS_ACCESS_KEY_ID=$(echo "$creds" | jq -r '.AccessKeyId')
  export AWS_SECRET_ACCESS_KEY=$(echo "$creds" | jq -r '.SecretAccessKey')
  export AWS_SESSION_TOKEN=$(echo "$creds" | jq -r '.SessionToken')

  # Check for OIDC providers
  oidc_providers=$(aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text 2>/dev/null)

  if ! echo "$oidc_providers" | grep -q "$OIDC_PROVIDER_NAME"; then
    echo "OIDC Provider '$OIDC_PROVIDER_NAME' not found in account $account_id"
    echo "$account_id" >> "$OUTPUT_FILE"
  else
    echo "OIDC Provider '$OIDC_PROVIDER_NAME' found in account $account_id"
  fi

  # Unset temporary credentials
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
done

echo "Done. Results saved to $OUTPUT_FILE"