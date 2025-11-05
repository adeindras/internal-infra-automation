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
  if [[ ${globalUserEmailSuperuser} == "" || ${globalUserEmailSuperuser} == null ]]; then
    echo "You Must Specify globalUserEmailSuperuser on preDefined dataSeed"
    exit 1
  fi
  if [[ ${globalUserPasswordSuperuser} == "" || ${globalUserPasswordSuperuser} == null ]]; then
    echo "You Must Specify globalUserPasswordSuperuser on preDefined dataSeed"
    exit 1
  fi
  if [[ ${namespacePublisher} == "" || ${namespacePublisher} == null ]]; then
    echo "You Must Specify namespacePublisher on preDefined dataSeed"
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

function generate_test_user() {
  echo -e "===> Create new Super User"
  curl -s --write-out "Response code : %{http_code}\n" \
  --request POST "${baseURLDirect}/iam/v4/admin/namespaces/${namespace}/test_users" \
  --header 'Accept: application/json' \
  --header "Authorization: Bearer ${accessToken}" \
  --header 'Content-Type: application/json' \
  --data-raw '{"count": 1}' --dump-header ${resHeader} -o ${rawJson}
		response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  if [[ ${response} == 201 ]]; then
  	tmpGlobalRoleIdUser=$(jq '.data[].userId' ${rawJson} | tr -d '"' | tr -d '\r')
    echo "userId: ${tmpGlobalRoleIdUser}"
    jq '.seed.userId = "'${tmpGlobalRoleIdUser}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}

    tmpUsername=$(jq '.data[].username' ${rawJson} | tr -d '"' | tr -d '\r')
    echo "username: ${tmpUsername}"
    jq '.seed.username = "'${tmpUsername}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}

    tmpEmailAddress=$(jq '.data[].emailAddress' ${rawJson} | tr -d '"' | tr -d '\r')
    echo "emailAddress: ${tmpEmailAddress}"
    jq '.seed.emailAddress = "'${tmpEmailAddress}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}

    tmpUserPassword=$(jq '.data[].password' ${rawJson} | tr -d '"' | tr -d '\r')
    jq '.seed.password = "'${tmpUserPassword}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}
  else
    failed "Seed globalRoleIdUser Fail"
  fi
  clean_response
  echo "===< End of globalRoleIdUser"$'\n'
}

function seed_assign_sys_role_super_admin() {
  echo "===> Seeding Assign Role for SysRole Super Admin"

  curl -s --write-out "Response code : %{http_code}\n" \
  --location --request GET "${baseURLDirect}/iam/v4/admin/roles?isWildcard=true&adminRole=true&limit=100" \
  --header 'Accept: application/json' \
  --header "Authorization: Bearer ${accessToken}" \
  --dump-header ${resHeader} -o ${rawJson}

  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  sysRoleSuperAdminRoleId=$(jq -r '[.data[] | select(.roleName == "Super Admin") | .roleId][0]' ${rawJson})
  if [[ ${response} == 200 ]]; then
    echo "Success Get RoleId System Role Super Admin"
  else
    failed "Fail Get RoleId System Role Super Admin"
  fi
  clean_response

  curl -s --write-out "Response code : %{http_code}\n" \
  --location --request GET "${baseURLDirect}/iam/v3/admin/namespaces/${namespacePublisher}/users" \
  --header 'Accept: application/json' \
  --header "Authorization: Bearer ${accessToken}" \
  --get --data-urlencode "emailAddress=${tmpEmailAddress}" \
  --dump-header ${resHeader} -o ${rawJson}

  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  publisherUserId=$(jq -r '.userId' ${rawJson})
  if [[ ${response} == 200 ]]; then
    echo "Success Get Publisher User Id"
  else
    failed "Fail Get Publisher User Id"
  fi
  clean_response

  curl -s --write-out "Response code : %{http_code}\n" \
  --request POST "${baseURLDirect}/iam/v4/admin/namespaces/${namespacePublisher}/users/${publisherUserId}/roles" \
  --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${accessToken}" \
  --data-raw '{
      "assignedNamespaces": [
        "*"
      ],
      "roleId": "'${sysRoleSuperAdminRoleId}'"
    }' --dump-header ${resHeader} -o ${rawJson}

  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  if [[ ${response} == 200 ]]; then
    echo "Success Assign Super Admin to System Role"
  else
    failed "Fail Assign Super Admin to System Role"
  fi
  clean_response
  echo "===< End of Seeding Assign Role for SysRole Super Admin"$'\n'
}

function seed_game_client(){
  echo "===> Seed game namespace client"
  gameClientId=$(uuidgen | tr -d '-')
  gameClientSecret=$(cat /dev/urandom | tr -dc A-Za-z0-9 | head -c 32)
  curl -s --write-out "Response code : %{http_code}\n" \
  --request POST "${baseURLDirect}/iam/v3/admin/namespaces/${namespace}/clients" \
  --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${accessToken}" \
  --data-raw '{
    "clientID": "'${gameClientId}'",
    "clientName": "Autorightsizing-Functional-Test",
    "namespace": "'${namespace}'",
    "redirectURI": "'${baseURLDirect}'/admin",
    "oauthClientType": "Confidential",
    "secret": "'${gameClientSecret}'",
    "baseURI": "https://example.net/player",
    "clientPermissions": []
  }' --dump-header ${resHeader} -o ${rawJson}
  
  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  if [[ ${response} == 201 ]]; then
    jq '.seed.gameClientId = "'${gameClientId}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}
    jq '.seed.gameClientSecret = "'${gameClientSecret}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}
  else
    failed "Seed globalClientIdPublisherGame / globalClientIdAdminPortalGame Fail"
  fi
  echo "===< End of Seed game namespace client"$'\n'
}

function main() {
  init
  check_required_data_seed
  # generate_test_user
  # seed_assign_sys_role_super_admin
  seed_game_client
}

main "$@"