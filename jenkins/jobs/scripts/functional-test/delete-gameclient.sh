#!/usr/bin/env bash

function clean_response() {
  echo "" >${resHeader}
  echo "" >${resBody}
  echo "" >${rawJson}
}

function failed() {
  echo $1
  cat ${resHeader}
  cat ${rawJson}
  cat ${resBody}
  exit 1
}

function init() {
  echo "Init"
  readonly orig_cwd="$PWD"
  readonly output_dir=${orig_cwd}/output
  readonly tmpFile=${output_dir}/tmp.txt
  mkdir -p ${output_dir}
  readonly resHeader=${output_dir}/resHeader.txt
  readonly resBody=${output_dir}/resBody.txt
  readonly rawJson=${output_dir}/rawJson.json
  clean_response

  readonly configmap=${output_dir}/configmap.json
  readonly secret=${output_dir}/secret.json
  services=${orig_cwd}/services.json

  dataSeed=data.json
}

function check_required_data_seed() {
	echo "Check Required Data"
  baseURLDirect=$(jq -r '.preDefined.baseURLDirect' ${dataSeed})
  namespace=$(jq -r '.preDefined.namespace' ${dataSeed})
  clientId=$(jq -r '.preDefined.clientId' ${dataSeed})
  clientSecret=$(jq -r '.preDefined.clientSecret' ${dataSeed})
  globalUserEmailSuperuser=$(jq -r '.preDefined.globalUserEmailSuperuser' ${dataSeed})
  globalUserPasswordSuperuser=$(jq -r '.preDefined.globalUserPasswordSuperuser' ${dataSeed})
  namespacePublisher=$(jq -r '.preDefined.namespacePublisher' ${dataSeed})
  gameClientId=$(jq -r '.seed.gameClientId' ${dataSeed})
  userId=$(jq -r '.seed.userId' ${dataSeed})
  globalDraftStoreId=$(jq -r '.seed.globalDraftStoreId' ${dataSeed})
  globalCategoryPath=$(jq -r '.seed.globalCategoryPath' ${dataSeed})
  currencyCode=$(jq -r '.preDefined.currencyCode' ${dataSeed})
  itemId=$(jq -r '.seed.itemId' ${dataSeed})

  if [[ ${baseURLDirect} == "" || ${baseURLDirect} == null ]]; then
    echo "You Must Specify baseURLDirect on preDefined dataSeed"
    exit 1
  fi
  if [[ ${namespace} == "" || ${namespace} == null ]]; then
    echo "You Must Specify namespace on preDefined dataSeed"
    exit 1
  fi
  if [[ ${clientId} == "" || ${clientId} == null ]]; then
    echo "You Must Specify clientId on preDefined dataSeed"
    exit 1
  fi
  if [[ ${clientSecret} == "" || ${clientSecret} == null ]]; then
    echo "You Must Specify clientSecret on preDefined dataSeed"
    exit 1
  fi
  echo "Check Required Complete"

  echo -e "===> Generate Super User Token"
	user_pass="${clientId}:${clientSecret}"
	encode_user_pass=$(echo -n "$user_pass" | base64 )
	token=$(echo $encode_user_pass | tr -d ' ')

	echo -e "\n>Token Exchange"
  curl -s --write-out "Response code : %{http_code}\n" \
  --request POST "${baseURLDirect}/iam/v3/oauth/token" \
  --header "Authorization: Basic ${token}" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --header 'Accept: application/json' \
  --data "grant_type=password&username=${globalUserEmailSuperuser}&password=${globalUserPasswordSuperuser}" -o ${rawJson}
  accessToken=$(cat ${rawJson} | jq '.access_token' | tr -d '"' | tr -d '\r')
  if [[ ${accessToken} == "" || ${accessToken} == null ]]; then
    failed "Token exchange failed!\n--------------------"
  else
    tmpSuperUserToken=${accessToken}
    echo "Token exchange success"
  fi
  clean_response
  echo "===< End of Generate Super User Token"$'\n'
}

function delete_user(){
  echo -e "===> Delete user"
  curl -s --write-out "Response code : %{http_code}\n" \
  --request DELETE "${baseURLDirect}/iam/v3/admin/namespaces/${namespace}/users/${userId}/information" \
  --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${accessToken}" --dump-header ${resHeader} -o ${rawJson}

  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  if [[ ${response} == 204 ]]; then
    echo "Delete User Admin: ${userId} Success"
  else
    failed "Delete User Admin Fail"
  fi
  clean_response
  echo "===<<< End of Cleanup User Admin"$'\n'
}

function delete_game_client(){
  echo -e "===> Delete game client"
  curl -s --write-out "Response code : %{http_code}\n" \
  --request DELETE "${baseURLDirect}/iam/v3/admin/namespaces/${namespace}/clients/${gameClientId}" \
  --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${accessToken}" --dump-header ${resHeader} -o ${rawJson}

  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  if [[ ${response} == 204 ]]; then
      echo "Delete game client id: ${gameClientId} Success"
  else
      failed "Delete globalClientIdPublisherGame / globalClientIdAdminPortalGame Fail"
  fi
  clean_response
  echo "===<<< End of Cleanup User Admin"$'\n'
}

function main() {
  init
  check_required_data_seed
  delete_game_client
  # delete_user
  jq '.seed = {}' ${dataSeed} >${tmpFile}
  cat ${tmpFile} >${dataSeed}
}

main "$@"