#!/bin/sh

#CUSTOMER_NAME=${CUSTOMER_NAME:=accelbyte}
#ENVIRONMENT_NAME=${ENVIRONMENT_NAME:=development}
#HOSTNAME=${HOSTNAME:=development.accelbyte.io}
#AWS_REGION=${AWS_REGION:=us-west-2}

# CUSTOMER_NAME=accelbyte
# ENVIRONMENT_NAME=development
# HOSTNAME=development.accelbyte.io
# AWS_REGION=us-west-2

CURL_EXTRA_ARGS=${CURL_EXTRA_ARGS:=""}

set -euo pipefail

preflight() {
  printf "${YELLOW}----->${LIGHT_BLUE} IAM V2 API Blocking Preflight Checks ${YELLOW}<-----${NC}\n"
  echo "no preflight found"
  printf "\n"
}

postflight() {
  printf "${YELLOW} ----->${LIGHT_BLUE} IAM V2 API Blocking Postflight Checks ${YELLOW}<-----${NC}\n"
  printf "${LIGHT_BLUE}AWS ACCOUNT ID${NC}: $(aws sts get-caller-identity --query 'Account')\n"

  printf "${LIGHT_BLUE}Retrieving Credentials ${NC}\n"
  PLATFORM_CLIENT_ID=$(aws ssm get-parameters --region ${AWS_REGION} --names "/eks/${CUSTOMER_NAME}/justice/${ENVIRONMENT_NAME}/justice-iam-service/platform_client_id" --with-decryption --query 'Parameters[0].Value' --output text)
  PLATFORM_CLIENT_SECRET=$(aws ssm get-parameters --region ${AWS_REGION} --names "/eks/${CUSTOMER_NAME}/justice/${ENVIRONMENT_NAME}/justice-iam-service/platform_client_secret" --with-decryption --query 'Parameters[0].Value' --output text)
  SUPERUSER_USERNAME=$(aws ssm get-parameters --region ${AWS_REGION} --names "/eks/${CUSTOMER_NAME}/justice/${ENVIRONMENT_NAME}/justice-iam-service/superuser" --with-decryption --query 'Parameters[0].Value' --output text)
  SUPERUSER_PASSWORD=$(aws ssm get-parameters --region ${AWS_REGION} --names "/eks/${CUSTOMER_NAME}/justice/${ENVIRONMENT_NAME}/justice-iam-service/superuser_password" --with-decryption --query 'Parameters[0].Value' --output text)

  IAM_BASIC_AUTH=$(echo -ne "${PLATFORM_CLIENT_ID}:${PLATFORM_CLIENT_SECRET}" | base64 -w 0 )

  AP_USER_ID=$(curl ${CURL_EXTRA_ARGS} -sXPOST "https://${HOSTNAME}/iam/oauth/token" \
    -H "accept: application/json" \
    -H "authorization: Basic ${IAM_BASIC_AUTH}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&username=${SUPERUSER_USERNAME}&password=${SUPERUSER_PASSWORD}" | \
    jq -r ".user_id"
  )

  AP_NAMESPACE=$(curl ${CURL_EXTRA_ARGS} -sXPOST "https://${HOSTNAME}/iam/oauth/token" \
    -H "accept: application/json" \
    -H "authorization: Basic ${IAM_BASIC_AUTH}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&username=${SUPERUSER_USERNAME}&password=${SUPERUSER_PASSWORD}" | \
    jq -r ".namespace"
  )

  requests=(
    # Format: "METHOD|URL|HEADERS|PAYLOAD"
    "GET|https://${HOSTNAME}/iam/v3/public/namespaces/${AP_NAMESPACE}/platforms/steam/users/${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users/byLoginId?loginId=${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users/byPlatformUserID?platformID=steam&platformUserID=${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users/listByLoginIds?loginIds=${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}/platforms|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam/v2/public/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam%2Fv3%2Fpublic%2Fnamespaces%2F${AP_NAMESPACE}%2Fplatforms%2Fsteam%2Fusers%2F${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2FbyLoginId%3FloginId%3D${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2FbyPlatformUserID%3FplatformID%3Dsteam%26platformUserID%3D${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2FlistByLoginIds%3FloginIds%3D${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2F${AP_USER_ID}%2Fplatforms|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "GET|https://${HOSTNAME}/iam%2Fv2%2Fpublic%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2F${AP_USER_ID}|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
  )

  requests_siblings=(
    # Format: "METHOD|URL|HEADERS|PAYLOAD"
    "POST|https://${HOSTNAME}/iam/v3/public/namespaces/${AP_NAMESPACE}/platforms/steam/users?rawPID=false&rawPUID=false|Content-Type: application/json;accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|{\"platformUserIds\":[\"value\"]}"
    "POST|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users|Content-Type: application/json;accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|{\"AuthType\": \"string\",\"Country\": \"string\",\"DisplayName\": \"string\",\"LoginId\": \"string\",\"Password\": \"string\",\"PasswordMD5Sum\": \"string\"}"
    "PUT|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}|Content-Type: application/json;accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|{\"Country\": \"string\",\"DateOfBirth\": \"string\",\"DisplayName\": \"string\",\"LanguageTag\": \"string\"}"
    "GET|https://${HOSTNAME}/iam/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}/bans|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
    "POST|https://${HOSTNAME}/iam%2Fv3%2Fpublic%2Fnamespaces%2F${AP_NAMESPACE}%2Fplatforms%2Fsteam%2Fusers%3FrawPID%3Dfalse%26rawPUID%3Dfalse|Content-Type: application/json;accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|{\"platformUserIds\":[\"value\"]}"
    "POST|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers|Content-Type: application/json;accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|{\"AuthType\": \"string\",\"Country\": \"string\",\"DisplayName\": \"string\",\"LoginId\": \"string\",\"Password\": \"string\",\"PasswordMD5Sum\": \"string\"}"
    "PUT|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2F${AP_USER_ID}|Content-Type: application/json;accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|{\"Country\": \"string\",\"DateOfBirth\": \"string\",\"DisplayName\": \"string\",\"LanguageTag\": \"string\"}"
    "GET|https://${HOSTNAME}/iam%2Fnamespaces%2F${AP_NAMESPACE}%2Fusers%2F${AP_USER_ID}%2Fbans|accept: application/json;Authorization: Basic ${IAM_BASIC_AUTH}|"
  )

  echo "USER_ID: ${AP_USER_ID}"
  echo "NS: ${AP_NAMESPACE}"
  echo "URL: https://${HOSTNAME}/iam"

  printf "${LIGHT_BLUE}Testing Endpoint ${NC}\n"
  USER_EP_CODE=$(curl ${CURL_EXTRA_ARGS} -sXGET "https://${HOSTNAME}/iam/v2/public/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}" \
    -H "accept: application/json" \
    -H "authorization: Basic ${IAM_BASIC_AUTH}" \
    -w "%{http_code}" \
    -o /dev/null
  )
  BAN_EP_CODE=$(curl ${CURL_EXTRA_ARGS} -sXGET "https://${HOSTNAME}/iam/v2/public/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}/bans?activeOnly=true" \
    -H "accept: application/json" \
    -H "authorization: Basic ${IAM_BASIC_AUTH}" \
    -w "%{http_code}" \
    -o /dev/null
  )
  PLATFORM_EP_CODE=$(curl ${CURL_EXTRA_ARGS} -sXGET "https://${HOSTNAME}/iam/v2/public/namespaces/${AP_NAMESPACE}/users/${AP_USER_ID}/platforms/justice" \
    -H "accept: application/json" \
    -H "authorization: Basic ${IAM_BASIC_AUTH}" \
    -w "%{http_code}" \
    -o /dev/null
  )

  if [[ "${USER_EP_CODE}" == "403" ]]
    then
      printf "${GREEN} --> OK, user endpoint successfully blocked${NC}\n"
    else
      printf "${RED} --> NOT OK, should be 403${NC}\n"
      printf "${RED} ----> CURRENT RESPONSE: ${USER_EP_CODE} ${NC}\n"
  fi
  
  if [[ "${BAN_EP_CODE}" == "404" ]]
    then
      printf "${GREEN} --> OK, ban endpoint not blocked${NC}\n"
    else
      printf "${RED} --> NOT OK, should be 404${NC}\n"
      printf "${RED} --> CURRENT RESPONSE: ${USER_EP_CODE} ${NC}\n"
  fi
  
  if [[ "${PLATFORM_EP_CODE}" == "200" ]]
    then
      printf "${GREEN} --> OK, platforms endpoint not blocked${NC}\n"
    else
      printf "${RED} --> NOT OK, should be 200${NC}\n"
      printf "${RED} --> CURRENT RESPONSE: ${USER_EP_CODE} ${NC}\n"
  fi

  for request in "${requests[@]}"; do
    IFS="|" read -r method url headers payload <<< "$request"
    check_response "$method" "$url" "$headers" "$payload" "false"
  done
  for request in "${requests_siblings[@]}"; do
    IFS="|" read -r method url headers payload <<< "$request"
    check_response "$method" "$url" "$headers" "$payload" "true"
  done


}

check_response() {
  local method=$1
  local url=$2
  local headers=$3
  local payload=$4
  local siblings=$5

  header_array=()
  IFS=";" read -r -a header_pairs <<< "$headers"
  for pair in "${header_pairs[@]}"; do
    header_array+=("-H" "$pair")
  done

  if [[ $method == "GET" || $method == "DELETE" ]]; then
    response=$(curl -s -D - -o /dev/null -w "HTTP_CODE:%{http_code}" -X "$method" "$url" \
      "${header_array[@]}" )
  else
    response=$(curl -s -D - -o /dev/null -w "HTTP_CODE:%{http_code}" -X "$method" "$url" \
      "${header_array[@]}" \
      -d "$payload")
  fi

  server_header=$(echo "$response" | grep -i "^server:" | awk '{print $2}' | tr -d " \t\n\r")
  http_code=$(echo "$response" | grep "HTTP_CODE:" | awk -F: '{print $2}' | tr -d " \t\n\r")

  echo "Method: $method"
  echo "URL: $url"
  echo "Payload: $payload"
  echo "Server response header: $server_header"
  echo "--------------------------"

  if [[ $siblings == "true" ]]; then
    if [[ $http_code != 403 ]]; then
      printf "${GREEN} --> OK, Access sibings endpoint not blocked and got response ${http_code} and server is ${server_header}${NC}\n"
    else
      printf "${RED} --> NOT OK, should not be 403${NC}\n"
    fi
  else
    if [[ $http_code == 403 && $server_header == "awselb/2.0" ]]; then
      printf "${GREEN} --> OK, Access endpoint successfully blocked from awselb/2.0 and got response ${http_code}${NC}\n"
    else
      printf "${RED} --> NOT OK, should be 403${NC}\n"
    fi
  fi
  
}