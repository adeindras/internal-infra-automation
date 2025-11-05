#!/bin/bash

# .\k6 run --vus 2 --duration 1m -e serviceName=platform -e testcaseName=test_platform wsLoadGenerator.js
# .\k6 run "$script_name"

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
    users_output="users_output.json"
    [ -f $users_output ] && rm $users_output
    readonly orig_cwd="$PWD"
    readonly output_dir=${orig_cwd}/output
    readonly tmpFile=${output_dir}/tmp.txt
    mkdir -p ${output_dir}
    readonly resHeader=${output_dir}/resHeader.txt
    readonly resBody=${output_dir}/resBody.txt
    readonly rawJson=${output_dir}/rawJson.json
    clean_response

    dataSeed=./config.json
    run_mode=$1
    script_name=$2
    k6_options=""
}

function check_execution_variable() {
    echo "Check Required Data"
    baseURLDirect=$(jq -r '.data[0].baseURLDirect' ${dataSeed})
    baseUrlWs=$(jq -r '.data[0].baseUrlWs' ${dataSeed})
    namespacePublisher=$(jq -r '.data[0].namespacePublisher' ${dataSeed})
    namespaceGame=$(jq -r '.data[0].namespaceGame' ${dataSeed})
    clientId=$(jq -r '.data[0].clientId' ${dataSeed})
    clientSecret=$(jq -r '.data[0].clientSecret' ${dataSeed})
    adminEmail=$(jq -r '.data[0].adminEmail' ${dataSeed})
    adminPassword=$(jq -r '.data[0].adminPassword' ${dataSeed})
    statCode=$(jq -r '.data[0].statCode' ${dataSeed})
    virtualCurrency=$(jq -r '.data[0].virtualCurrency' ${dataSeed})
    sessionTemplate=$(jq -r '.data[0].sessionTemplate' ${dataSeed})
    matchRuleset=$(jq -r '.data[0].matchRuleset' ${dataSeed})
    matchPool=$(jq -r '.data[0].matchPool' ${dataSeed})
    if [[ ${baseURLDirect} == "" || ${baseURLDirect} == null ]]; then
        echo "You Must Specify baseURLDirect on preDefined dataSeed"
    exit 1
    fi
    if [[ ${namespaceGame} == "" || ${namespaceGame} == null ]]; then
        echo "You Must Specify namespaceGame on preDefined dataSeed"
    exit 1
    fi
    if [[ ${clientId} == "" || ${clientId} == null ]]; then
        echo "You Must Specify clientId on preDefined dataSeed"
    exit 1
    fi
    if [[ "$CLIENT_TYPE" == "PUBLIC" ]]; then
        echo "Public client mode, client secret is not needed"
    elif [[ ${clientSecret} == "" || ${clientSecret} == null ]]; then
        echo "You Must Specify clientSecret on preDefined dataSeed"
    exit 1
    fi
    if [[ ${adminEmail} == "" || ${adminEmail} == null ]]; then
        echo "You Must Specify adminEmail on preDefined dataSeed"
    exit 1
    fi
    if [[ ${adminPassword} == "" || ${adminPassword} == null ]]; then
        echo "You Must Specify adminPassword on preDefined dataSeed"
    exit 1
    fi
    if [[ ${namespacePublisher} == "" || ${namespacePublisher} == null ]]; then
        echo "You Must Specify namespacePublisher on preDefined dataSeed"
    exit 1
    fi

    if [[ $# -lt 2 ]]; then
        echo "Usage: $0 <mode: docker|local> <script_name> [vus] [duration] [service_name] [testcase_name] [max_backoff_duration]"
        exit 1
    fi

    if [[ "$script_name" != "wsLoadGenerator.js" && "$script_name" != "loadGenerator.js" ]]; then
        echo "Invalid script name. Please specify either 'wsLoadGenerator.js' or 'loadGenerator.js'."
        exit 1
    fi

    if [[ $# -gt 2 ]]; then
        if [[ ! "$3" =~ ^[0-9]+$ ]]; then
            echo "Invalid VUs value. Please enter a positive integer."
            exit 1
        fi
        vus="$3"
        k6_options="$k6_options -e NUMBER_OF_USER=$vus"
    fi

    if [[ $# -gt 3 ]]; then
        if [[ ! "$4" =~ ^([0-9]+)([mshd]) ]]; then
            echo "Invalid runtime duration format. Please use 'mshd' for milliseconds, seconds, hours, or days."
            exit 1
        fi
        runtime_duration="$4"
        k6_options="$k6_options -e MAX_DURATION=$runtime_duration"
    fi

    if [[ $# -gt 4 ]]; then
        if [[ -z "$5" || ! "$5" =~ ^[[:alnum:]]+((,[[:alnum:]]+))*$ ]]; then
            echo "The services name does not match the pattern."
            exit 1
        fi
        services_name="$5"
        k6_options="$k6_options -e serviceName=$services_name"
    fi

    if [[ $# -gt 5 ]]; then
        if [[ -z "$6" || ! "$6" =~ ^(test_[[:alnum:]_]+)(,(test_[[:alnum:]_]+))*$ ]]; then
            echo "The testcase name does not match the pattern."
            exit 1
        fi
        testcase_name="$6"
        k6_options="$k6_options -e testcaseName=$testcase_name"
    fi
    
    if [[ $# -gt 6 ]]; then
        if [[ -z "$7" || ! "$7" =~ ^[0-9]+$ ]]; then
            echo "The max backoff duration does not match the pattern."
            exit 1
        fi
        max_backoff_duration="$7"
        k6_options="$k6_options -e maxBackoffDuration=$max_backoff_duration"
    fi

    echo -e "===> Generate Super User Token"
    user_pass="${clientId}:${clientSecret}"
    encode_user_pass=$(echo -n "$user_pass" | base64 )
    token=$(echo $encode_user_pass | tr -d ' ')
    echo $adminEmail
    echo -e "\n>Token Exchange"
    curl -s --write-out 'Response code: %{http_code}\n' \
      -X 'POST' \
      "${baseURLDirect}/iam/v3/oauth/token" \
      -H 'accept: application/json' \
      -H "authorization: Basic ${token}" \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      -d "grant_type=password&username=${adminEmail}&password=${adminPassword}" \
      -o ${rawJson}
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

function seed_game_client(){
  echo "===> Seed game namespace client"
  gameClientId=$(head -c 16 /dev/urandom | od -An -t x1 | tr -d ' \n' | sed 's/\(.\{12\}\).\{1\}/\14/' | sed 's/\(.\{16\}\).\{1\}/\18/')
  gameClientSecret=$(cat /dev/urandom | tr -dc A-Za-z0-9 | head -c 32)
  clientPermissions=$(jq -rc '.' client_permissions.json)
  curl -s --write-out "Response code : %{http_code}\n" \
  --request POST "${baseURLDirect}/iam/v3/admin/namespaces/${namespaceGame}/clients" \
  --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${accessToken}" \
  --data-raw '{
    "clientID": "'${gameClientId}'",
    "clientName": "LoadGenerationTool-Test",
    "namespace": "'${namespaceGame}'",
    "redirectURI": "'${baseURLDirect}'/admin",
    "oauthClientType": "Confidential",
    "secret": "'${gameClientSecret}'",
    "baseURI": "https://example.net/player",
    "clientPermissions": '${clientPermissions}'
  }' --dump-header ${resHeader} -o ${rawJson}
  
  response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
  if [[ ${response} == 201 ]]; then
    jq '.seed[0].gameClientId = "'${gameClientId}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}
    jq '.seed[0].gameClientSecret = "'${gameClientSecret}'"' ${dataSeed} >${tmpFile}
    cat ${tmpFile} >${dataSeed}
  else
    failed "Seed globalClientIdPublisherGame / globalClientIdAdminPortalGame Fail"
  fi
  echo "===< End of Seed game namespace client"$'\n'
}

function seed_stat_code(){
    echo "===> Seed Stat Code"
    curl -s --write-out "Response code : %{http_code}\n" \
    --request POST "${baseURLDirect}/social/v1/admin/namespaces/${namespaceGame}/stats" \
    --header 'Content-Type: application/json' \
    --header 'accept: application/json' \
    --header "Authorization: Bearer ${accessToken}" \
    --data '{
        "statCode": "'${statCode}'",
        "name": "StatcodeLGT",
        "defaultValue": 0,
        "setBy": "CLIENT"
    }' --dump-header ${resHeader} -o ${rawJson}

    response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
    if [[ ${response} == 201 || ${response} == 409 ]]; then
        jq '.seed[0].statCode = "'${statCode}'"' ${dataSeed} >${tmpFile}
        cat ${tmpFile} >${dataSeed}
    else
        failed "Seed StatCode Fail"
    fi
    echo "===< End of Seed Stat Code"$'\n'
}

function seed_virtual_currency(){
    echo "===> Seed virtual currency"
    curl -s --write-out "Response code : %{http_code}\n" \
    --request POST "${baseURLDirect}/platform/admin/namespaces/${namespaceGame}/currencies" \
    --header 'Content-Type: application/json' \
    --header 'accept: application/json' \
    --header "Authorization: Bearer ${accessToken}" \
    --data '{
      "currencyCode": "'${virtualCurrency}'",
      "currencySymbol": "'${virtualCurrency}'",
      "currencyType": "VIRTUAL",
      "decimals": 0
    }' --dump-header ${resHeader} -o ${rawJson}

    response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
    if [[ ${response} == 200 || ${response} == 409 ]]; then
        jq '.seed[0].virtualCurrency = "'${virtualCurrency}'"' ${dataSeed} >${tmpFile}
        cat ${tmpFile} >${dataSeed}
    else
        failed "Seed virtual currency Fail"
    fi
    echo "===< End of Seed Virtual currency"$'\n'
}

function seed_session_template(){
    echo "===> Seed session template"
    curl -s --write-out "Response code : %{http_code}\n" \
    --request POST "${baseURLDirect}/session/v1/admin/namespaces/${namespaceGame}/configuration" \
    --header 'Content-Type: application/json' \
    --header 'accept: application/json' \
    --header "Authorization: Bearer ${accessToken}" \
    --data '{
      "name": "'${sessionTemplate}'",
      "type": "NONE",
      "autoJoin": false,
      "joinability": "OPEN",
      "immutableStorage": false,
      "persistent": false,
      "textChat": false,
      "inactiveTimeout": 300,
      "inviteTimeout": 100,
      "maxActiveSessions": -1,
      "maxPlayers": 3,
      "minPlayers": 1
    }' --dump-header ${resHeader} -o ${rawJson}

    response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
    if [[ ${response} == 201 || ${response} == 409 ]]; then
        jq '.seed[0].sessionTemplate = "'${sessionTemplate}'"' ${dataSeed} >${tmpFile}
        cat ${tmpFile} >${dataSeed}
    else
        failed "Seed session template Fail"
    fi
    echo "===< End of Seed session template"$'\n'
}

function seed_matchruleset(){
    echo "===> Seed match ruleset"
    curl -s --write-out "Response code : %{http_code}\n" \
    --request POST "${baseURLDirect}/match2/v1/namespaces/${namespaceGame}/rulesets" \
    --header 'Content-Type: application/json' \
    --header 'accept: application/json' \
    --header "Authorization: Bearer ${accessToken}" \
    --data '{
      "name": "'${matchRuleset}'",
      "data": {
        "alliance": {
            "min_number":        2,
            "max_number":        3,
            "player_min_number": 1,
            "player_max_number": 1
        }
      },
      "enable_custom_match_function": false
    }' --dump-header ${resHeader} -o ${rawJson}

    response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
    if [[ ${response} == 201 || ${response} == 409 ]]; then
        jq '.seed[0].matchRuleset = "'${matchRuleset}'"' ${dataSeed} >${tmpFile}
        cat ${tmpFile} >${dataSeed}
    else
        failed "Seed match ruleset Fail"
    fi
    echo "===< End of Seed match ruleset"$'\n'
}

function seed_matchpool(){
    echo "===> Seed match pool"
    curl -s --write-out "Response code : %{http_code}\n" \
    --request POST "${baseURLDirect}/match2/v1/namespaces/${namespaceGame}/match-pools" \
    --header 'Content-Type: application/json' \
    --header 'accept: application/json' \
    --header "Authorization: Bearer ${accessToken}" \
    --data '{
      "name": "'${matchPool}'",
      "match_function": "default",
      "rule_set": "'${matchRuleset}'",
      "session_template": "'${sessionTemplate}'",
      "ticket_expiraton_seconds": 300,
      "backfill_ticket_expiraton_seconds": 200,
      "backfill_proposal_expiraton_seconds": 100,
      "ticket_expiraton_seconds": 60,
      "auto_accept_backfill_proposal": true
    }' --dump-header ${resHeader} -o ${rawJson}

    response=$(cat ${resHeader} | grep HTTP/ | tail -1 | awk -F ' ' '{print $2}')
    if [[ ${response} == 201 || ${response} == 409 ]]; then
        jq '.seed[0].matchPool = "'${matchPool}'"' ${dataSeed} >${tmpFile}
        cat ${tmpFile} >${dataSeed}
    else
        failed "Seed match pool Fail"
    fi
    echo "===< End of Seed match pool"$'\n'
}

function cleanup() {
    if [[ -n $CLEANED ]]; then
        exit
    fi
    CLEANED=yes
    echo "Cleaning up..."
    case "$run_mode" in
        "local-windows")
            k6.exe run --no-summary $k6_options "./teardown.js"
            ;;
        "local-docker")
            docker run --rm -v "$(pwd):/load-generation-tool" k6-load-generator-tool run --no-summary $k6_options "/load-generation-tool/teardown.js"
            ;;
        "linux")
            k6 run --no-summary $k6_options "./teardown.js"
            ;;
    esac
}

function main() {
    K6_IMAGE="k6-load-generator-tool"
    trap 'cleanup "$@"' EXIT
    trap 'echo "Interrupted by Ctrl+C"; cleanup "$@"; exit 1' SIGINT
    init $@
    check_execution_variable $@
    seed_game_client $@
    seed_stat_code $@
    seed_virtual_currency $@
    seed_session_template $@
    seed_matchruleset $@
    seed_matchpool $@

    echo "Running k6 in mode: $run_mode with command: k6 run $k6_options"

    case "$run_mode" in
        "local-windows")
            ./k6.exe run --no-summary -e NUMBER_OF_USER="$vus" "./test/testCreateTestUsers.js"
            sleep 3
            ./k6.exe run $k6_options "$script_name"
            ;;
        "local-docker")
            docker run --rm -v "$(pwd):/load-generation-tool" -e NUMBER_OF_USER="$vus" k6-load-generator-tool run --no-summary "/load-generation-tool/test/testCreateTestUsers.js"
            sleep 3
            docker run --rm -v "$(pwd):/load-generation-tool" k6-load-generator-tool run $k6_options "/load-generation-tool/$script_name"
            ;;
        "linux")
            k6 run --no-summary -e NUMBER_OF_USER="$vus" "./test/testCreateTestUsers.js"
            sleep 3
            k6 run $k6_options "$script_name" --out json=metrics.json --summary-export result.json
            ;;
        "linux-prometheus")
            k6 run --no-summary -e NUMBER_OF_USER="$vus" "./test/testCreateTestUsers.js"
            sleep 3
            K6_PROMETHEUS_RW_USERNAME=${PROMETHEUS_USERNAME} K6_PROMETHEUS_RW_PASSWORD=${PROMETHEUS_PASSWORD} K6_PROMETHEUS_RW_SERVER_URL=${PROMETHEUS_SERVER_URL} k6 run $k6_options "$script_name" --out experimental-prometheus-rw --summary-export result.json
            ;;
        *)
            echo "Invalid mode. Use 'local-windows', 'local-docker', or 'linux'."
            exit 1
            ;;
    esac

    echo "k6 run completed."
}

main "$@"
