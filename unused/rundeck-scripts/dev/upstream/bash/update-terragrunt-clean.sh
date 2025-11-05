#!/bin/bash

# load vars
# CURRENT_TIMESTAMP=$(date +%s)

TARGET_ENVIRONMENT_NAME="$RD_OPTION_TARGET_ENVIRONMENT_NAME"
TARGET_CCU="$RD_OPTION_TARGET_CCU"
APPLIED_RESOURCES="$RD_OPTION_APPLIED_RESOURCES"

AWS_ACCOUNT_ID="$(echo $TARGET_ENVIRONMENT_NAME | cut -f1 -d',')"
AWS_REGION="$(echo $TARGET_ENVIRONMENT_NAME | cut -f3 -d',')"
CUSTOMER_NAME="$(echo $TARGET_ENVIRONMENT_NAME | cut -f2 -d',' | cut -f1 -d'-')"
PROJECT_NAME="$(echo $TARGET_ENVIRONMENT_NAME | cut -f2 -d',' | cut -f2 -d'-')"
ENVIRONMENT_NAME="$(echo $TARGET_ENVIRONMENT_NAME | cut -f2 -d',' | cut -f3 -d'-')"

# cloned IAC repo
REPO_DIR="@data.TEMP_DIR@/iac"

TEMPLATE_WORKSPACE="accelbyte"
TEMPLATE_REPO_NAME="internal-infra-automation"
TEMPLATE_BRANCH_NAME="master"
TEMPLATE_FILE_PATH="templates/latest.json"
BITBUCKET_APP_KEY="$RD_OPTION_BITBUCKET_APP_KEY"

# curl -s -S --user "${USERNAME}:${PASSWORD}" -L -O "https://api.bitbucket.org/2.0/repositories/${WORKSPACE}/${REPO_NAME}/src/${BRANCH_NAME}/${FILE_PATH}"
scaling_template=$(curl -s -S -H "Authorization: Basic ${BITBUCKET_APP_KEY}" "https://api.bitbucket.org/2.0/repositories/${TEMPLATE_WORKSPACE}/${TEMPLATE_REPO_NAME}/src/${TEMPLATE_BRANCH_NAME}/${TEMPLATE_FILE_PATH}")

# scaling_template=$(cat <<< $get_template | yq '[{"name": .metadata.name, "template-type": .metadata.label.template-type}]')
# get_len=$(cat <<< $list | yq length)

# HCL manipulation functions

function turn_tginputs_to_block() {
    get_inputs_string=$(grep -cE '^inputs.*[=].*[{]' terragrunt.hcl)

    if [[ $get_inputs_string == "1" ]]; then
        sed -i --regexp-extended 's/^inputs.*[=].*[{]/inputs {/g' terragrunt.hcl
    fi
}

function turn_tginputs_to_nested_var() {
    get_inputs_string=$(grep -cE '^inputs.*[{]' terragrunt.hcl)

    if [[ $get_inputs_string == "1" ]]; then
        sed -i --regexp-extended 's/^inputs.*[{]/inputs = {/g' terragrunt.hcl
        # terragrunt hclfmt
    fi
}

function modify_rds_justice() {
    #source_instance_type=$(cat terragrunt.hcl | grep rds_instance_class | awk '{print $3}' | tr -d '"')
    tg_variable_name="rds_instance_class"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').rds_justice_instance_type')
}

function modify_rds_analytics() {
    #source_instance_type=$(cat terragrunt.hcl | grep rds_instance_class | awk '{print $3}' | tr -d '"')
    tg_variable_name="rds_instance_class"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').rds_analytics_instance_type')
}

function modify_opensearch() {
    #source_instance_type=$(cat terragrunt.hcl | grep instance_type | awk '{print $3}' | tr -d '"')
    tg_variable_name="instance_type"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').opensearch_instance_type')
}

function modify_msk() {
    #source_instance_type=$(cat terragrunt.hcl | grep broker_instance_type | awk '{print $3}' | tr -d '"')
    tg_variable_name="broker_instance_type"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').msk_instance_type')
}

function modify_docdb() {
    #source_instance_type=$(cat terragrunt.hcl | grep instance_class | awk '{print $3}' | tr -d '"')
    tg_variable_name="instance_class"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').docdb_instance_type')
}

function modify_elasticache() {
    #source_instance_type=$(cat terragrunt.hcl | grep instance_type | awk '{print $3}' | tr -d '"')
    tg_variable_name="instance_type"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').elasticache_instance_type')
}

function modify_rds_justice_replica() {
    # echo "modifying rds_justice_replica"
    #source_instance_type=$(cat terragrunt.hcl | grep rds_instance_type | awk '{print $3}' | tr -d '"')
    tg_variable_name="rds_instance_type"
    target_instance_type=$(cat <<< ${scaling_template} | yq '.[] | select(.ccu == '"${TARGET_CCU}"').rds_justice_replica_instance_type')
}

pushd "$REPO_DIR" > /dev/null

for i in $APPLIED_RESOURCES; do

    # As of now, we do not have support for modifying custom/splitted resources
    if [[ $i == "rds_justice" ]]; then
        resource_dir="rds/justice"
    elif [[ $i == "rds_justice_replica" ]]; then
        resource_dir="rds/justice-replica"
    elif [[ $i == "rds_analytics" ]]; then
        resource_dir="rds/analytics"
    elif [[ $i == "opensearch" ]]; then
        resource_dir="opensearch/logging"
    elif [[ $i == "elasticache" ]]; then
        resource_dir="elasticache/justice"
    else
        resource_dir="$i"
    fi

    if [[ -d $PWD/live/$AWS_ACCOUNT_ID/$CUSTOMER_NAME/$PROJECT_NAME/$AWS_REGION/$ENVIRONMENT_NAME/$resource_dir/ ]]; then
        echo "[+] ${resource_dir} exists, updating instance type configuration ..."
        pushd live/$AWS_ACCOUNT_ID/$CUSTOMER_NAME/$PROJECT_NAME/$AWS_REGION/$ENVIRONMENT_NAME/$resource_dir/ > /dev/null
        "modify_$i"
        
        if [[ -z ${target_instance_type} ]] | [[ ${target_instance_type} == "" ]]; then
            echo "[SKIPPED] Configuration profile does not exist for this resource."
        else
            turn_tginputs_to_block
            hcledit -f terragrunt.hcl attribute set "inputs.${tg_variable_name}" "\"${target_instance_type}\"" -u
            # sed -i "s/${source_instance_type}/${target_instance_type}/g" terragrunt.hcl
            turn_tginputs_to_nested_var
        fi
        
        popd > /dev/null
    fi

done