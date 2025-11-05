#!/bin/bash

turn_tginputs_to_block() {
    get_inputs_string=$(grep -cE '^inputs.*[=].*[{]' terragrunt.hcl)
    if [[ $get_inputs_string == "1" ]]; then
        sed -i --regexp-extended 's/^inputs.*[=].*[{]/inputs {/g' terragrunt.hcl
    fi
}

turn_tginputs_to_nested_var() {
    get_inputs_string=$(grep -cE '^inputs.*[{]' terragrunt.hcl)
    if [[ $get_inputs_string == "1" ]]; then
        sed -i --regexp-extended 's/^inputs.*[{]/inputs = {/g' terragrunt.hcl
    fi
}

modify_terragrunt() {
    key_name=$1
    value=$2
    hcledit -f terragrunt.hcl attribute set "inputs.${key_name}" "${value}" -u
}

check_rds() {
    msa_resource_data=$1

    rds_resource_data="$(echo "${msa_resource_data}" | jq -r '.[] | select(.type == "rds").data[].name')"
    is_replica_exist="false"

    if echo "${msa_resource_data}" | jq -e '.[] | select(.type == "rds").data[].replicas[].name' >/dev/null 2>&1; then
        is_replica_exist="true"
        echo "replica is exist"
    fi

    if [[ $is_replica_exist == "true" ]]; then
        rds_replica_resource_data="$(echo "${msa_resource_data}" | jq -r '.[] | select(.type == "rds").data[].replicas[].name')"
        rds_list="$rds_resource_data $rds_replica_resource_data"
    fi

    if [[ $is_replica_exist == "false" ]]; then
        rds_list="$rds_resource_data"
    fi

    echo "${rds_list}" | while read -r rds_name; do
        resource_dir="rds/"

        if [[ "$rds_name" == "shared" ]]; then
            rds_type="main"
            rds_group_dir="justice-shared"

            if [[ -d "rds/justice-shared-pg16" ]]; then
                rds_group_dir="justice-shared-pg16"
            fi

            # add here if the directory has changed
            # if [[ -d "rds/<new-directory-name>" ]]; then
            #     rds_group_dir="<new-directory-name>"
            # fi
            # todo: create cronjob to daily check if there's any new pattern and report to slack

            resource_dir="rds/${rds_group_dir}"
        elif [[ "$rds_name" == "analytics" ]]; then
            rds_type="main"
            rds_group_dir="analytics-shared"

            if [[ -d "rds/analytics-shared-pg16" ]]; then
                rds_group_dir="analytics-shared-pg16"
            fi

            # add here if the directory has changed
            # if [[ -d "rds/<new-directory-name>" ]]; then
            #     rds_group_dir="<new-directory-name>"
            # fi
            # todo: create cronjob to daily check if there's any new pattern and report to slack

            resource_dir="rds/${rds_group_dir}"
        elif [[ "$(echo "$rds_name" | grep -cE "\-replica*")" -eq 1 ]]; then
            rds_type="replica"
            rds_group_dir="${rds_name}"
            resource_dir="rds/${rds_group_dir}"
        else
            rds_type="main"
            rds_group_dir="${rds_name}"
            resource_dir="rds/${rds_group_dir}"
        fi

        if [[ "${rds_type}" == "main" ]]; then
            resource_instance_type="$(echo "${msa_resource_data}" | jq --arg resource_name "$rds_name" '.[] | select(.type == "rds").data[] | select(.name == $resource_name).class')"
        elif [[ "${rds_type}" == "replica" ]]; then
            resource_instance_type="$(echo "${msa_resource_data}" | jq --arg resource_name "$rds_name" '.[] | select(.type == "rds").data[].replicas[] | select(.name == $resource_name).class')"
        fi

        if [[ -d "${resource_dir}" ]]; then
            echo "Found ${resource_dir} - modifying..."
            pushd "${resource_dir}" >/dev/null
            turn_tginputs_to_block
            modify_terragrunt "rds_instance_class" "${resource_instance_type}"
            turn_tginputs_to_nested_var
            popd >/dev/null
        else
            echo "${resource_dir} not found"
        fi
    done
}

check_msk() {
    msa_resource_data=$1

    resource_dir="msk"
    msk_resource_data="$(echo "${msa_resource_data}" | jq -r -c '.[] | select(.type == "msk").data[].name')"

    echo "${msk_resource_data}" | while read -r msk_name; do
        if [[ "$msk_name" == "all" ]]; then
            msk_group_dir="justice-shared"

            if [[ -d "msk/justice-shared-updated" ]]; then
                msk_group_dir="justice-shared-updated"
            fi

            if [[ -d "msk/justice-shared-update" ]]; then
                msk_group_dir="justice-shared-update"
            fi

            # add here if the directory has changed
            # if [[ -d "msk/<new-directory-name>" ]]; then
            #     msk_group_dir="<new-directory-name>"
            # fi
            # todo: create cronjob to daily check if there's any new pattern and report to slack

            msk_service=all
            resource_dir="msk/${msk_group_dir}"
        else
            msk_group_dir="$msk_name"
            resource_dir="msk/${msk_group_dir}"
            msk_service="$msk_group_dir"
        fi

        resource_instance_type="$(echo "$msa_resource_data" | jq --arg resource_name "$msk_service" '.[] | select(.type == "msk").data[] | select(.name == $resource_name).class')"
        resource_brokers="$(echo "$msa_resource_data" | jq --arg resource_name "$msk_service" '.[] | select(.type == "msk").data[] | select(.name == $resource_name).brokers')"
        resource_partitions="$(echo "$msa_resource_data" | jq --arg resource_name "$msk_service" '.[] | select(.type == "msk").data[] | select(.name == $resource_name).partitions')"

        if [[ -d "${resource_dir}" ]]; then
            echo "Found ${resource_dir} - modifying..."
            pushd "${resource_dir}" >/dev/null
            turn_tginputs_to_block
            modify_terragrunt "broker_instance_type" "${resource_instance_type}"
            modify_terragrunt "number_of_broker_nodes" "${resource_brokers}"
            modify_terragrunt "num_partitions" "${resource_partitions}"
            turn_tginputs_to_nested_var
            popd >/dev/null
        else
            echo "${resource_dir} not found"
        fi
    done
}

check_docdb() {
    msa_resource_data=$1

    resource_dir="docdb"
    docdb_resource_data="$(echo "${msa_resource_data}" | jq -r -c '.[] | select(.type == "docdb").data[].name')"

    echo "${docdb_resource_data}" | while read -r docdb_name; do
        new_directory_structure="false"

        if [[ -d "docdb/justice-shared" ]]; then
            new_directory_structure="true"
        fi

        if [[ $new_directory_structure == "true" ]]; then
            if [[ "$docdb_name" == "shared" ]]; then
                docdb_service="shared"
                docdb_group_dir="justice-shared"
                resource_dir="docdb/${docdb_group_dir}"

                # add here if the directory has changed
                # if [[ -d "docdb/<new-directory-name>" ]]; then
                #     docdb_group_dir="<new-directory-name>"
                # fi
                # todo: create cronjob to daily check if there's any new pattern and report to slack
            else
                docdb_service="${docdb_name}"
                docdb_group_dir="${docdb_name}"
                resource_dir="docdb/${docdb_group_dir}"
            fi
        fi

        if [[ $new_directory_structure == "false" ]]; then
            if [[ "$docdb_name" == "shared" ]]; then
                docdb_service="${docdb_name}"
                resource_dir="docdb"
            else
                docdb_service="${docdb_name}"
                docdb_group_dir="${docdb_name}"
                resource_dir="docdb-dedicated/${docdb_group_dir}"
            fi
        fi

        resource_instance_type="$(echo "$msa_resource_data" | jq --arg resource_name "$docdb_service" '.[] | select(.type == "docdb").data[] | select(.name == $resource_name).class')"

        if [[ -d "${resource_dir}" ]]; then
            echo "Found ${resource_dir} - modifying..."
            pushd "${resource_dir}" >/dev/null
            turn_tginputs_to_block
            modify_terragrunt "instance_class" "${resource_instance_type}"
            turn_tginputs_to_nested_var
            popd >/dev/null
        else
            echo "${resource_dir} not found"
        fi
    done
}

check_elasticache() {
    msa_resource_data=$1

    resource_dir="elasticache"
    elasticache_resource_data="$(echo "${msa_resource_data}" | jq -r -c '.[] | select(.type == "elasticache").data[].name')"

    echo "${elasticache_resource_data}" | while read -r elasticache_name; do
        if [[ "$elasticache_name" == "shared" ]]; then
            elasticache_service="shared"
            elasticache_group_dir="justice-shared"

            # add here if the directory has changed
            # if [[ -d "elasticache/<new-directory-name>" ]]; then
            #     elasticache_group_dir="<new-directory-name>"
            # fi
            # todo: create cronjob to daily check if there's any new pattern and report to slack

            resource_dir="elasticache/${elasticache_group_dir}"
        else
            elasticache_service="${elasticache_name}"
            elasticache_group_dir="${elasticache_name}"
            resource_dir="elasticache/${elasticache_group_dir}"
        fi

        resource_instance_type="$(echo "$msa_resource_data" | jq --arg resource_name "$elasticache_service" '.[] | select(.type == "elasticache").data[] | select(.name == $resource_name).class')"

        if [[ -d "${resource_dir}" ]]; then
            echo "Found ${resource_dir} - modifying..."
            pushd "${resource_dir}" >/dev/null
            turn_tginputs_to_block
            modify_terragrunt "instance_type" "${resource_instance_type}"
            turn_tginputs_to_nested_var
            popd >/dev/null
        else
            echo "${resource_dir} not found"
        fi
    done
}

check_opensearch() {
    msa_resource_data=$1

    resource_dir="opensearch"
    opensearch_resource_data="$(echo "${msa_resource_data}" | jq -r -c '.[] | select(.type == "opensearch").data[].name')"

    echo "${opensearch_resource_data}" | while read -r opensearch_name; do
        if [[ "$opensearch_name" == "all" ]]; then
            opensearch_service="${opensearch_name}"
            opensearch_group_dir="logging"

            # add here if the directory has changed
            # if [[ -d "elasticache/<new-directory-name>" ]]; then
            #     opensearch_group_dir="<new-directory-name>"
            # fi
            # todo: create cronjob to daily check if there's any new pattern and report to slack

            resource_dir="opensearch/${opensearch_group_dir}"
        else
            opensearch_service="${opensearch_name}"
            opensearch_group="${opensearch_name}"
            resource_dir="opensearch/${opensearch_group}"
        fi

        resource_instance_type="$(echo "$msa_resource_data" | jq --arg resource_name "$opensearch_service" '.[] | select(.type == "opensearch").data[] | select(.name == $resource_name).class')"

        if [[ -d "${resource_dir}" ]]; then
            echo "Found ${resource_dir} - modifying..."
            pushd "${resource_dir}" >/dev/null
            turn_tginputs_to_block
            modify_terragrunt "instance_type" "${resource_instance_type}"
            turn_tginputs_to_nested_var
            popd >/dev/null
        else
            echo "${resource_dir} not found"
        fi
    done
}

main() {
    ENCODED_MSA_RESOURCES_DATA=$1
    CLIENT_NAME=$2
    PROJECT_NAME=$3
    ENVIRONMENT_NAME=$4

    if [[ -z "$ENCODED_MSA_RESOURCES_DATA" ]]; then
        echo "ENCODED_MSA_RESOURCES_DATA parameter is missing"
        return 1
    fi

    if [[ -z "$CLIENT_NAME" ]]; then
        echo "CLIENT_NAME parameter is missing"
        return 1
    fi

    if [[ -z "$PROJECT_NAME" ]]; then
        echo "PROJECT_NAME parameter is missing"
        return 1
    fi

    if [[ -z "$ENVIRONMENT_NAME" ]]; then
        echo "ENVIRONMENT_NAME parameter is missing"
        return 1
    fi

    MSA_RESOURCES_DATA="$(echo -ne "${ENCODED_MSA_RESOURCES_DATA}" | base64 -d)"

    if [[ -d iac ]]; then
        cd iac
    fi

    TG_DIRECTORY=$(find . | grep -E "${CLIENT_NAME}\/${PROJECT_NAME}\/*\/.*\/${ENVIRONMENT_NAME}\/eks$" | sed 's/eks//g')
    echo "${TG_DIRECTORY}"
    cd "${TG_DIRECTORY}" || exit 1
    pwd

    # logic starts here
    echo "$MSA_RESOURCES_DATA" | jq -r -c '.[].type' | while read -r LINE; do
        # rds
        if [[ "${LINE}" == "rds" ]]; then
            echo "+ Checking RDS..."
            check_rds "$MSA_RESOURCES_DATA"
            # msk
        elif [[ "${LINE}" == "msk" ]]; then
            echo "+ Checking MSK..."
            check_msk "$MSA_RESOURCES_DATA"
        # docdb
        elif [[ "${LINE}" == "docdb" ]]; then
            echo "+ Checking DocDB..."
            check_docdb "$MSA_RESOURCES_DATA"
        # elasticache
        elif [[ "${LINE}" == "elasticache" ]]; then
            echo "+ Checking ElastiCache..."
            check_elasticache "$MSA_RESOURCES_DATA"
        # opensearch
        elif [[ "${LINE}" == "opensearch" ]]; then
            echo "+ Checking Opensearch..."
            check_opensearch "$MSA_RESOURCES_DATA"
        else
            echo "+ Unknown resource type"
            echo "+ resource data: ${LINE}"
        fi
    done
}

main "$@"
