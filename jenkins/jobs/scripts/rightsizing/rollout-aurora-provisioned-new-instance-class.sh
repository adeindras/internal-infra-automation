#!/bin/bash
# Copyright (c) 2017-2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

# initial author : Muhammad Rizky Amrullah, Rightsize Team (rizky.amrullah@accelbyte.net)

# latest start  : 2025-10-03 13:55:04 UTC [INFO] [script.sh] validate_preconditions triggered
# latest end    : 2025-10-03 14:19:49 UTC [INFO] [script.sh] Rightsizing process completed successfully ✅

# ./script.sh \
#   --account-id 342674635073 \
#   --instance-class db.t3.medium \
#   --region us-west-2 \
#   --cluster rds-aurora-accelbyte-justice-stage-justice-test-cluster \
#   --path /home/ubuntu/iac-2/live/342674635073/accelbyte/justice/us-west-2/stage/rds_aurora/provisioned/justice-shared-test \
#   --rightsize-hcl-editor-path /home/ubuntu/bin/rightsize-hcl-editor
#   --skip-terragrunt-apply true

set -eo pipefail

SCRIPT_NAME="$(basename "$0")"

# default max supported read replicas per primary: 15 (ref: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/CHAP_Limits.html#RDS_Limits.Limits)
# default max instances -> 15 replica + 1 primary: 16 instances
INSTANCES_BLOCK_KEY=(
  "one"
  "two"
  "three"
  "four"
  "five"
  "six"
  "seven"
  "eight"
  "nine"
  "ten"
  "eleven"
  "twelve"
  "thirteen"
  "fourteen"
  "fiveteen"
  "sixteen"
)

EXISTING_INSTANCES=""
TEMP_READER_KEY=""
FORMER_WRITER_KEY=""
CURRENT_WRITER_KEY=""

print_usage() {
    echo
    echo "This script is used to rightsize Aurora Provisioned cluster with minimum to zero interruptions."
    echo
    echo "Author: Muhammad Rizky Amrullah, Rightsize Team (rizky.amrullah@accelbyte.net)"
    echo
    echo "Usage: $SCRIPT_NAME [OPTIONS]"
    echo
    echo "Options:"
    echo -e "  -c, --cluster\t\t\tAurora Provisioned cluster name / identifier (required)."
    echo -e "  -i, --instance-class\t\tAurora Provisioned desired instance class (required)."
    echo -e "  -r, --region\t\t\tAurora Provisioned AWS Region (required)."
    echo -e "  -p, --path\t\t\tPath to Aurora Provisioned cluster terragrunt manifest (required)."
    echo -e "  --account-id\t\t\tAurora Provisioned AWS Account ID (required)."
    echo -e "  --platform\t\t\tScript platform (required), supported values: \"autorightsizing\" and \"rightsize-tools\"."
    echo -e "  --rightsize-hcl-editor-path\tPath to rightsize-hcl-editor binary."
    echo -e "                             \tSource code (Bitbucket): accelbyte/internal-infra-automation/apps/rightsize-hcl-editor"
    echo
    echo "Example: "
    echo "  Rightsize Aurora Provisioned to db.r6g.xlarge in us-west-2 region: "
    echo "  $ $SCRIPT_NAME \ "
    echo "      --account-id 123456789123 \ "
    echo "      --instance-class db.r6g.xlarge \ "
    echo "      --region us-west-2 \ "
    echo "      --cluster rds-aurora-accelbyte-justice-stage-justice-pg16-cluster \ "
    echo "      --path /path/to/rds_aurora/provisioned/justice_test \ "
    echo "      --rightsize-hcl-editor-path /home/ubuntu/bin/rightsize-hcl-editor"
    echo
    echo
}

log() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date +"%Y-%m-%d %H:%M:%S")
    echo >&2 -e "${timestamp} [${level}] [$SCRIPT_NAME] ${message}"
}

log_info() {
    local message="$1"
    log "INFO" "$message"
}

log_warn() {
    local message="$1"
    log "WARN" "$message"
}

log_error() {
    local message="$1"
    log "ERROR" "$message"
}

assert_not_empty() {
    local arg_name="$1"
    local arg_value="$2"

    if [[ -z "$arg_value" ]]; then
        log_error "The value for '$arg_name' cannot be empty"
        print_usage
        exit 1
    fi
}

assert_is_option_used() {
    local arg_name="$1"
    local variable_value="$2"

    if [[ -z "$variable_value" ]]; then
        log_error "$arg_name option has to be included"
        print_usage
        exit 1
    fi
}

assert_is_installed() {
    local -r name="$1"
    if [[ ! $(command -v "${name}") ]]; then
        log_error "The binary '$name' is required by this script but is not installed or in the system's PATH."
        exit 1
    fi
}

assert_is_rightsize_hcl_editor_correct() {
    local -r name="$1"
    if [[ ! -f "$name" ]]; then
        log_error "The binary '$name' is required by this script but the given binary is not valid or not exist."
        exit 1
    fi
}

cleanup_on_failure() {
    # capture the exit code of the script.
    last_exit_code=$?

    if [[ "$last_exit_code" -ne 0 ]]; then
        log_info "🔴 Script failed with exit code $last_exit_code. Performing failure cleanup..."
        # add failure-specific cleanup here.
    else
        log_info "🟢 Script completed successfully. Performing general cleanup..."
    fi
        # add cleanup tasks that should run regardless of success or failure here.
}

turn_tginputs_to_block() {
    get_inputs_string=$(grep -c 'inputs = {' terragrunt.hcl)
    if [[ $get_inputs_string == "1" ]]; then
        sed -i 's/inputs = {/inputs {/' terragrunt.hcl
    fi
}

turn_instance_to_block() {
    local key=$1
    is_key_exist="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep -q "$key = {"; echo $?)"
    if [[ "$is_key_exist" == "0" ]]; then
        sed -i "s/$key = {/$key {/" terragrunt.hcl
    fi
}

turn_tginstances_to_block() {
    get_instances_string=$(grep -c "instances = {" terragrunt.hcl)
    if [[ $get_instances_string == "1" ]]; then
        sed -i 's/instances = {/instances {/' terragrunt.hcl
    fi
}

turn_tginputs_to_nested_var() {
    get_inputs_string=$(grep -c 'inputs {' terragrunt.hcl)
    if [[ $get_inputs_string == "1" ]]; then
        sed -i 's/inputs {/inputs = {/' terragrunt.hcl
    fi
}

turn_tginstances_to_nested_var() {
    get_instances_string=$(grep -c 'instances {' terragrunt.hcl)
    if [[ $get_instances_string == "1" ]]; then
        sed -i 's/instances {/instances = {/' terragrunt.hcl
    fi
}

simulate_terragrunt_apply() {
    log_info "running terragrunt apply.."
    # sleep 5
    log_info "successfully ran terragrunt apply ✅"
}

turn_instance_block_to_nested_envar() {
    local key=$1
    is_key_exist="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep -q "$key {"; echo $?)"
    if [[ "$is_key_exist" == "0" ]]; then
        sed -i "s/$key {/$key = {/" terragrunt.hcl
    fi
}

validate_conditions() {
    local cluster_name=$1
    local desired_instance_class=$2
    local aurora_aws_region=$3
    local aurora_aws_account_id=$4
    local terragrunt_full_path=$5
    log_info "validate_conditions triggered"
    log_info "cluster id: $cluster_name"
    log_info "desired instance class: $desired_instance_class"
    log_info "aws region: $aurora_aws_region"

    log_info "check if given path has terragrunt.hcl file in it.."
    if [[ ! -f "$terragrunt_full_path/terragrunt.hcl" ]]; then
        log_error "the given path has no terragrunt.hcl file ❌"
        log_error "the given path: $terragrunt_full_path"
        return 1
    fi
    log_info "terragrunt.hcl file exist in the given path ✅"

    log_info "check if used aws credentials' account id match with the aurora acount id.."
    aws_credentials_account_id="$(aws sts get-caller-identity --no-cli-pager --query 'Account' --output text)"
    if [[ "$aws_credentials_account_id" != "$aurora_aws_account_id" ]]; then
        log_error "used aws credentials doesn't match with the aurora account id ❌"
        return 1
    fi
    log_info "used aws credentials match with the aurora account id ✅"

    log_info "check if aurora provisioned cluster $cluster_name exist.."
    is_aurora_exist="$(aws rds describe-db-clusters --region "$aurora_aws_region" --no-cli-pager --db-cluster-identifier "$cluster_name" &> /dev/null; echo $?)"
    if [[ "$is_aurora_exist" != "0" ]]; then
        log_error "aurora provisioned cluster $cluster_name is not exist ❌"
        return 1
    fi
    log_info "aurora provisioned cluster with cluster id $cluster_name is exist ✅"

    log_info "check if aurora provisioned cluster $cluster_name is in available state.."
    aurora_status="$(aws rds describe-db-clusters --region "$aurora_aws_region" --no-cli-pager --db-cluster-identifier "$cluster_name" --query 'DBClusters[].Status' --output text)"
    if [[ "$aurora_status" != "available" ]]; then
        log_error "aurora provisioned cluster $cluster_name is not in available state ❌"
        return 1
    fi
    log_info "aurora provisioned cluster $cluster_name is in available state ✅"

    log_info "check if aurora provisioned cluster $cluster_name has one writer.."
    is_writer_exist="$(aws rds describe-db-clusters --region "$aurora_aws_region" --no-cli-pager --db-cluster-identifier "$cluster_name" --query 'DBClusters[].DBClusterMembers[].IsClusterWriter' --output text | grep -q "True"; echo $?)"
    if [[ "$is_writer_exist" != "0" ]]; then
        log_error "aurora provisioned cluster $cluster_name has no writer instance ❌"
        return 1
    fi
    log_info "aurora provisioned cluster $cluster_name has one writer ✅"

    log_info "check if aurora provisioned cluster $cluster_name has exactly one writer and at least one reader.."
    is_reader_exist="$(aws rds describe-db-clusters --region "$aurora_aws_region" --no-cli-pager --db-cluster-identifier "$cluster_name" --query 'DBClusters[].DBClusterMembers[].IsClusterWriter' --output text | grep -q "False"; echo $?)"
    if [[ "$is_reader_exist" != "0" ]]; then
        log_warn "aurora provisioned cluster $cluster_name has no reader instance ⚠️"
    else
        log_info "aurora provisioned cluster $cluster_name has exactly one writer and at least one reader ✅"
    fi

    pushd "${terragrunt_full_path}" >/dev/null      
    log_info "check if terragrunt.hcl file is valid.."
    if ! terragrunt validate; then
        log_error "the terragrunt.hcl file is not valid ❌"
        return 1
    fi
    log_info "terragrunt.hcl file is valid ✅"
    popd >/dev/null
}

provision_temp_reader_for_no_reader() {
    local cluster_name=$1
    local desired_instance_class=$2
    local aws_region=$3
    local terragrunt_full_path=$4
    log_info "provision_temp_reader_for_no_reader triggered"

    pushd "${terragrunt_full_path}" >/dev/null

    # identify blocks
    # - default max supported read replicas per primary: 15 (ref: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/CHAP_Limits.html#RDS_Limits.Limits)
    # - default max instances -> 15 replica + 1 primary: 16 instances
    # - instances block key: ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fiveteen", "sixteen"]
    # - existing instances blocks key: one, two
    # - new reader block key: instances_block_key - existing_instances_block_key, then pick instances_block_key[0] (e.g., "three")

    # how to know existing instances block key?
    turn_tginputs_to_block
    turn_tginstances_to_block

    current_writer_identifier="$(aws rds describe-db-clusters --db-cluster-identifier "$cluster_name" --region "$aws_region" --query "DBClusters[].DBClusterMembers[?IsClusterWriter==\`true\`].DBInstanceIdentifier" --output text --no-cli-pager)"
    current_instance_class="$(aws rds describe-db-instances --region "$aws_region" --no-cli-pager --db-instance-identifier "$current_writer_identifier" --query "DBInstances[].DBInstanceClass" --output text)"

    log_info "provision a temporary reader on cluster $cluster_name from $current_instance_class to $desired_instance_class on $aws_region region.."

    FORMER_WRITER_KEY="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep "= {" | awk '{print $1}')"

    log_info "proceed to change instance $FORMER_WRITER_KEY promotion_tier.."
    turn_instance_to_block "$FORMER_WRITER_KEY"
    if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$FORMER_WRITER_KEY.promotion_tier" -value '"1"'; then
        log_error "something went wrong during instance $FORMER_WRITER_KEY promotion_tier modification ❌"
        return 1
    else
        log_info "successfully set instance $FORMER_WRITER_KEY promotion_tier to '1' ✅"
    fi

    if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$FORMER_WRITER_KEY.instance_class" -value "\"$current_instance_class\""; then
        log_error "something went wrong during instance $FORMER_WRITER_KEY instance_class modification ❌"
        return 1
    else
        log_info "successfully set instance $FORMER_WRITER_KEY instance_class to '$current_instance_class' ✅"
    fi
    turn_instance_block_to_nested_envar "$FORMER_WRITER_KEY"

    for key in "${INSTANCES_BLOCK_KEY[@]}"; do
        IS_EXIST="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep -q "$key = {"; echo $?)"
        if [[ "$IS_EXIST" != "0" ]]; then
            TEMP_READER_KEY="$key"
            break
        fi
    done

    log_info "adding temporary reader block called '$TEMP_READER_KEY'.."
    if [[ "$TEMP_READER_KEY" == "one" ]]; then
        "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$TEMP_READER_KEY" -value "{ identifier = local.name, instance_class = \"$desired_instance_class\", promotion_tier = \"0\"}"
    else
        "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$TEMP_READER_KEY" -value "{ identifier = format(\"%s-$TEMP_READER_KEY\", local.name), instance_class = \"$desired_instance_class\", promotion_tier = \"0\"}"
    fi

    turn_instance_block_to_nested_envar "$TEMP_READER_KEY"

    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var
    
    # simulate_terragrunt_apply
    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
        simulate_terragrunt_apply
    else
        terragrunt apply --auto-approve
    fi

    popd >/dev/null
    log_info "a new reader is successfully provisioned ✅"
}

provision_temp_reader_for_more_than_one_but_less_than_fiveteen_readers() {
    local cluster_name=$1
    local desired_instance_class=$2
    local aws_region=$3
    local terragrunt_full_path=$4
    log_info "provision_temp_reader_for_more_than_one_but_less_than_fiveteen_readers triggered"

    pushd "${terragrunt_full_path}" >/dev/null

    # identify blocks
    # - default max supported read replicas per primary: 15 (ref: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/CHAP_Limits.html#RDS_Limits.Limits)
    # - default max instances -> 15 replica + 1 primary: 16 instances
    # - instances block key: ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fiveteen", "sixteen"]
    # - existing instances blocks key: one, two
    # - new reader block key: instances_block_key - existing_instances_block_key, then pick instances_block_key[0] (e.g., "three")

    # how to know existing instances block key?
    turn_tginputs_to_block
    turn_tginstances_to_block

    current_writer_identifier="$(aws rds describe-db-clusters --db-cluster-identifier "$cluster_name" --region "$aws_region" --query "DBClusters[].DBClusterMembers[?IsClusterWriter==\`true\`].DBInstanceIdentifier" --output text --no-cli-pager)"
    current_instance_class="$(aws rds describe-db-instances --region "$aws_region" --no-cli-pager --db-instance-identifier "$current_writer_identifier" --query "DBInstances[].DBInstanceClass" --output text)"
    if [[ "$current_writer_identifier" == "${cluster_name%-*}" ]]; then
        CURRENT_WRITER_KEY="one"
    else
        CURRENT_WRITER_KEY="${current_writer_identifier##*-}"
    fi

    log_info "provision a temporary reader on cluster $cluster_name from $current_instance_class to $desired_instance_class on $aws_region region.."

    EXISTING_INSTANCES="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep "= {" | awk '{print $1}')"
    echo "$EXISTING_INSTANCES" | while read -r instance; do
        if [[ "$instance" == "$CURRENT_WRITER_KEY" ]]; then
            turn_instance_to_block "$instance"
            if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$instance.promotion_tier" -value '"1"'; then
                log_error "something went wrong during instance $instance promotion_tier modification ❌"
                return 1
            else
                log_info "successfully set instance $instance promotion_tier to '1' ✅"
            fi

            if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$instance.instance_class" -value "\"$current_instance_class\""; then
                log_error "something went wrong during instance $instance instance_class modification ❌"
                return 1
            else
                log_info "successfully set instance $instance instance_class to '$current_instance_class' ✅"
            fi
            turn_instance_block_to_nested_envar "$instance"

            continue
        fi

        log_info "proceed to change instance $instance promotion_tier.."
        turn_instance_to_block "$instance"
        if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$instance.promotion_tier" -value '"0"'; then
            log_error "something went wrong during instance $instance promotion_tier modification ❌"
            return 1
        else
            log_info "successfully set instance $instance promotion_tier to '0' ✅"
        fi

        if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$instance.instance_class" -value "\"$current_instance_class\""; then
            log_error "something went wrong during instance $instance instance_class modification ❌"
            return 1
        else
            log_info "successfully set instance $instance instance_class to '$current_instance_class' ✅"
        fi
        turn_instance_block_to_nested_envar "$instance"
    done

    for key in "${INSTANCES_BLOCK_KEY[@]}"; do
        IS_EXIST="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep -q "$key = {"; echo $?)"
        if [[ "$IS_EXIST" != "0" ]]; then
            TEMP_READER_KEY="$key"
            break
        fi
    done

    log_info "adding temporary reader block called '$TEMP_READER_KEY'.."
    if [[ "$TEMP_READER_KEY" == "one" ]]; then
        "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$TEMP_READER_KEY" -value "{ identifier = local.name, instance_class = \"$desired_instance_class\", promotion_tier = \"15\"}"
    else
        "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$TEMP_READER_KEY" -value "{ identifier = format(\"%s-$TEMP_READER_KEY\", local.name), instance_class = \"$desired_instance_class\", promotion_tier = \"15\"}"
    fi

    turn_instance_block_to_nested_envar "$TEMP_READER_KEY"

    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var
    
    # simulate_terragrunt_apply
    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
        simulate_terragrunt_apply
    else
        terragrunt apply --auto-approve
    fi

    popd >/dev/null
    log_info "a new reader is successfully provisioned ✅"
}

wait_for_low_replication_lag() {
    log_info "wait_for_low_replication_lag triggered"
    log_info "monitoring the replication lag for at least 3-5 minutes.."
    # sleep 5
    log_info "replication lag is consistently low ✅"
}

promote_temp_reader() {
    local cluster_name=$1
    local aws_region=$2
    log_info "promote_temp_reader triggered"
    log_info "promote the temporary reader as the writer.."
    # ensure the temp reader instance has promotion_tier higher than the rest of reader instances
    # perform failover and ensure the writer role lands on the new reader using aws cli
    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "FALSE" ]]; then

        aws rds failover-db-cluster --db-cluster-identifier "$cluster_name" --region "$aws_region" --no-cli-pager
        # perform loop to check whether the new reader is successfully promoted to writer or not

        while true; do
            current_writer="$(aws rds describe-db-clusters --db-cluster-identifier "$cluster_name" --region "$aws_region" --query "DBClusters[].DBClusterMembers[?IsClusterWriter==\`true\`].DBInstanceIdentifier" --output text --no-cli-pager)"

            log_info "current writer is $current_writer"

            if [[ "$TEMP_READER_KEY" == "one" ]] && [[ "$current_writer" == "${cluster_name%-*}" ]]; then
                log_info "temp reader has been promoted as the writer"
                break
            fi
            
            if [[ "$current_writer" == "${cluster_name%-*}-$TEMP_READER_KEY" ]]; then
                log_info "temp reader has been promoted as the writer"
                break
            fi

            log_info "temp reader haven't promoted as the writer"
            log_info "proceed to try again in 5 seconds"
            sleep 5

            # todo: add timeout and figure out what todo if timeout occurred
        done
    fi    

    log_info "temp reader is successfully promoted as the writer ✅"
}

promote_former_writer() {
    local cluster_name=$1
    local aws_region=$2
    log_info "promote_former_writer triggered"
    log_info "promote back the former writer as the current writer.."
    # ensure the temp reader instance has promotion_tier higher than the rest of reader instances
    # perform failover and ensure the writer role lands on the new reader using aws cli
    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "FALSE" ]]; then

        aws rds failover-db-cluster --db-cluster-identifier "$cluster_name" --region "$aws_region" --no-cli-pager
        # perform loop to check whether the new reader is successfully promoted to writer or not

        while true; do
            current_writer="$(aws rds describe-db-clusters --db-cluster-identifier "$cluster_name" --region "$aws_region" --query "DBClusters[].DBClusterMembers[?IsClusterWriter==\`true\`].DBInstanceIdentifier" --output text --no-cli-pager)"

            log_info "current writer is $current_writer"

            if [[ "$FORMER_WRITER_KEY" == "one" ]] && [[ "$current_writer" == "${cluster_name%-*}" ]]; then
                log_info "former writer has been promoted as the current writer"
                break
            fi
            
            if [[ "$current_writer" == "${cluster_name%-*}-$FORMER_WRITER_KEY" ]]; then
                log_info "former writer has been promoted as the current writer"
                break
            fi

            log_info "former writer have not promoted as the current writer"
            log_info "proceed to try again in 5 seconds"
            sleep 5

            # todo: add timeout and figure out what todo if timeout occurred
        done
    fi    

    log_info "former writer is successfully promoted as the current writer ✅"
}

promote_reader_for_more_than_one_but_less_than_fiveteen_readers() {
    local cluster_name=$1
    local aws_region=$2
    log_info "promote_reader_for_more_than_one_but_less_than_fiveteen_readers triggered"
    log_info "promote one of the readers (except temporary reader) as the writer.."
    # ensure the temp reader instance has promotion_tier higher than the rest of reader instances
    # perform failover and ensure the writer role lands on the new reader using aws cli
    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "FALSE" ]]; then

        aws rds failover-db-cluster --db-cluster-identifier "$cluster_name" --region "$aws_region" --no-cli-pager
        # perform loop to check whether the new reader is successfully promoted to writer or not

        while true; do
            current_writer="$(aws rds describe-db-clusters --db-cluster-identifier "$cluster_name" --region "$aws_region" --query "DBClusters[].DBClusterMembers[?IsClusterWriter==\`true\`].DBInstanceIdentifier" --output text --no-cli-pager)"

            if [[ "$CURRENT_WRITER_KEY" == "one" ]] && [[ "$current_writer" == "${cluster_name%-*}" ]]; then
                log_info "one of the readers haven't promoted as the writer"
                log_info "proceed to try again in 5 seconds"
                sleep 5

                continue
            fi

            if [[ "$current_writer" == "${cluster_name%-*}-$CURRENT_WRITER_KEY" ]]; then
                log_info "one of the readers haven't promoted as the writer"
                log_info "proceed to try again in 5 seconds"
                sleep 5

                continue
            fi

            break

            # todo: add timeout and figure out what todo if timeout occurred
        done
    fi    

    log_info "one of the readers is successfully promoted as the writer ✅"
}

resize_readers_exclude_temp_reader_for_more_than_one_but_less_than_fiveteen_readers() {
    local desired_instance_class=$1
    local terragrunt_full_path=$2

    log_info "resize_readers_exclude_temp_reader_for_more_than_one_but_less_than_fiveteen_readers triggered"
    log_info "proceed to readers except current writer and temp reader instances.."

    pushd "${terragrunt_full_path}" >/dev/null

    echo "$EXISTING_INSTANCES" | while read -r instance; do
        if [[ "$instance" == "$CURRENT_WRITER_KEY" ]]; then
            continue
        fi

        if [[ "$instance" == "$TEMP_READER_KEY" ]]; then
            continue
        fi

        log_info "proceed to rightsize instance '$instance'.."

        turn_tginputs_to_block
        turn_tginstances_to_block
        turn_instance_to_block "$instance"

        "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$instance.instance_class" -value "\"$desired_instance_class\""
        
        turn_instance_block_to_nested_envar "$instance"
        turn_tginstances_to_nested_var
        turn_tginputs_to_nested_var

        if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
            simulate_terragrunt_apply
        else
            terragrunt apply --auto-approve
        fi
    done

    popd >/dev/null
    log_info "old instances is successfully rightsized ✅"
}

rightsize_former_writer_instance_for_no_reader() {
    local desired_instance_class=$1
    local terragrunt_full_path=$2

    log_info "rightsize_former_writer_instance_for_no_reader triggered"
    log_info "proceed to rightsize former writer instance.."

    pushd "${terragrunt_full_path}" >/dev/null

    log_info "proceed to rightsize instance '$FORMER_WRITER_KEY'.."

    turn_tginputs_to_block
    turn_tginstances_to_block

    turn_instance_to_block "$FORMER_WRITER_KEY"
    "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$FORMER_WRITER_KEY.instance_class" -value "\"$desired_instance_class\""
    "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$FORMER_WRITER_KEY.promotion_tier" -value "\"0\""    
    turn_instance_block_to_nested_envar "$FORMER_WRITER_KEY"

    turn_instance_to_block "$TEMP_READER_KEY"
    "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$TEMP_READER_KEY.promotion_tier" -value "\"1\""
    turn_instance_block_to_nested_envar "$TEMP_READER_KEY"

    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var

    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
        simulate_terragrunt_apply
    else
        terragrunt apply --auto-approve
    fi

    popd >/dev/null
    log_info "former writer instance is successfully rightsized ✅"
}

rightsize_former_writer_instance_for_more_than_one_but_less_than_fiveteen_readers() {
    local desired_instance_class=$1
    local terragrunt_full_path=$2

    log_info "rightsize_former_writer_instance_for_more_than_one_but_less_than_fiveteen_readers triggered"
    log_info "proceed to rightsize former writer instance.."

    pushd "${terragrunt_full_path}" >/dev/null

    log_info "proceed to rightsize instance '$CURRENT_WRITER_KEY'.."

    turn_tginputs_to_block
    turn_tginstances_to_block
    turn_instance_to_block "$CURRENT_WRITER_KEY"

    "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$CURRENT_WRITER_KEY.instance_class" -value "\"$desired_instance_class\""  

    turn_instance_block_to_nested_envar "$CURRENT_WRITER_KEY"
    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var

    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
        simulate_terragrunt_apply
    else
        terragrunt apply --auto-approve
    fi

    popd >/dev/null
    log_info "former writer instance is successfully rightsized ✅"
}

decommission_temp_reader() {
    local terragrunt_full_path=$1
    log_info "decommission_temp_reader triggered"
    # trigger decommission thru terragrunt
    # remove the instance block -> run terragrunt apply

    pushd "${terragrunt_full_path}" >/dev/null
    turn_tginputs_to_block
    turn_tginstances_to_block
    turn_instance_to_block "$TEMP_READER_KEY"
    "$RIGHTSIZE_HCL_EDITOR_PATH" delete -file terragrunt.hcl -address "inputs.instances.$TEMP_READER_KEY"
    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var

    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
        simulate_terragrunt_apply
    else
        terragrunt apply --auto-approve
    fi

    popd >/dev/null
    log_info "temp reader is successfully decommissioned ✅"
}

set_promotion_tier_to_zero() {
    local terragrunt_full_path=$1

    log_info "set_promotion_tier_to_zero triggered"
    log_info "proceed to set all instances promotion_tier / priority_tier to zero.."

    pushd "${terragrunt_full_path}" >/dev/null

    turn_tginputs_to_block
    turn_tginstances_to_block

    "$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep "= {" | awk '{print $1}' | while read -r instance; do
        log_info "proceed to change instance $instance promotion_tier.."
        turn_instance_to_block "$instance"
        if ! "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instances.$instance.promotion_tier" -value '"0"'; then
            log_error "something went wrong during change instance $instance promotion_tier ❌"
            return 1
        else
            log_info "successfully change instance $instance promotion_tier to '0' ✅"
        fi
        turn_instance_block_to_nested_envar "$instance"
    done

    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var

    if [[ "$IS_TERRAGRUNT_APPLY_SKIPPED" == "TRUE" ]]; then
        simulate_terragrunt_apply
    else
        terragrunt apply --auto-approve
    fi

    popd >/dev/null
    log_info "instances promotion_tier / priority_tier is successfully set to zero ✅"
}

remove_instance_class_and_promotion_tier_from_instances_block() {
    local desired_instance_class=$1
    local terragrunt_full_path=$2
    log_info "remove_instance_class_and_promotion_tier_from_instances_block triggered"
    pushd "${terragrunt_full_path}" >/dev/null
    turn_tginputs_to_block
    turn_tginstances_to_block

    "$RIGHTSIZE_HCL_EDITOR_PATH" set -file terragrunt.hcl -address "inputs.instance_class" -value "\"$desired_instance_class\""

    "$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address inputs.instances | grep "= {" | awk '{print $1}' | while read -r instance; do
        turn_instance_to_block "$instance"

        is_instance_class_exist="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address "inputs.instances.$instance" | grep -q "instance_class"; echo $?)"
        if [[ "$is_instance_class_exist" == "0" ]]; then
            "$RIGHTSIZE_HCL_EDITOR_PATH" delete -file terragrunt.hcl -address "inputs.instances.$instance.instance_class"
        fi

        is_promotion_tier_exist="$("$RIGHTSIZE_HCL_EDITOR_PATH" get -file terragrunt.hcl -address "inputs.instances.$instance" | grep -q "promotion_tier"; echo $?)"
        if [[ "$is_promotion_tier_exist" == "0" ]]; then
            "$RIGHTSIZE_HCL_EDITOR_PATH" delete -file terragrunt.hcl -address "inputs.instances.$instance.promotion_tier"
        fi

        turn_instance_block_to_nested_envar "$instance"
    done

    turn_tginstances_to_nested_var
    turn_tginputs_to_nested_var
}

run() {
    local cluster_name=$1
    local desired_instance_class=$2
    local aws_region=$3
    local aws_account_id=$4
    local terragrunt_full_path=$5

    validate_conditions "$cluster_name" "$desired_instance_class" "$aws_region" "$aws_account_id" "$terragrunt_full_path"

    reader_count="$(aws rds describe-db-clusters --region "$aws_region" --no-cli-pager --db-cluster-identifier "$cluster_name" --query "DBClusters[].DBClusterMembers[?IsClusterWriter==\`false\`].DBInstanceIdentifier" --output text | wc -l)"


    if [[ "$reader_count" -eq 0 ]]; then
        # no reader strategy:
        # 1. provision temp reader
        # 2. failover to temp reader
        # 3. resize former writer
        # 4. failover to former writer
        # 5. decommission temp reader

        log_info "cluster $cluster_name has no reader, proceed to go with no reader strategy"
        provision_temp_reader_for_no_reader "$cluster_name" "$desired_instance_class" "$aws_region" "$terragrunt_full_path"
        promote_temp_reader "$cluster_name" "$aws_region"
        rightsize_former_writer_instance_for_no_reader "$desired_instance_class" "$terragrunt_full_path"
        promote_former_writer "$cluster_name" "$aws_region"
        decommission_temp_reader "$terragrunt_full_path"
        set_promotion_tier_to_zero "$terragrunt_full_path"
        remove_instance_class_and_promotion_tier_from_instances_block "$desired_instance_class" "$terragrunt_full_path"
    elif [[ "$reader_count" -ge 1 ]] && [[ "$reader_count" -lt 15 ]]; then
        # strategy:
        # 1. provision temp reader
        # 2. resize readers one at a time except temp reader
        # 3. perform failover so one of the readers (except temp reader) become writer
        # 4. resize former writer
        # 5. decommission temp reader
    
        log_info "cluster $cluster_name has 1 writer and $reader_count reader"
        provision_temp_reader_for_more_than_one_but_less_than_fiveteen_readers "$cluster_name" "$desired_instance_class" "$aws_region" "$terragrunt_full_path"
        resize_readers_exclude_temp_reader_for_more_than_one_but_less_than_fiveteen_readers "$desired_instance_class" "$terragrunt_full_path"
        promote_reader_for_more_than_one_but_less_than_fiveteen_readers "$cluster_name" "$aws_region"
        rightsize_former_writer_instance_for_more_than_one_but_less_than_fiveteen_readers "$desired_instance_class" "$terragrunt_full_path"
        decommission_temp_reader "$terragrunt_full_path"
        set_promotion_tier_to_zero "$terragrunt_full_path"
        remove_instance_class_and_promotion_tier_from_instances_block "$desired_instance_class" "$terragrunt_full_path"
    elif [[ "$reader_count" -eq 15 ]]; then
        log_info "support for aurora provisioned with 15 readers is coming soon.."
    else
        log_info "we only support max 15 replicas, please contact us via slack if you need more."
    fi
}

main() {

    if [[ -z $1 ]]; then
        print_usage
        exit
    fi

    IS_TERRAGRUNT_APPLY_SKIPPED="FALSE"

    while [[ $# -gt 0 ]]; do
        local key="$1"

        case "$key" in
        -c)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_CLUSTER_NAME="$2"
            shift
            ;;
        --cluster)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_CLUSTER_NAME="$2"
            shift
            ;;
        -i)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_DESIRED_INSTANCE_CLASS="$2"
            shift
            ;;
        --instance-class)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_DESIRED_INSTANCE_CLASS="$2"
            shift
            ;;
        -r)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_AWS_REGION="$2"
            shift
            ;;
        --region)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_AWS_REGION="$2"
            shift
            ;;
        -p)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_TERRAGRUNT_FULL_PATH="$2"
            shift
            ;;
        --path)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_TERRAGRUNT_FULL_PATH="$2"
            shift
            ;;
        --account-id)
            assert_not_empty "$key" "$2"
            AURORA_PROVISIONED_AWS_ACCOUNT_ID="$2"
            shift
            ;;
        --rightsize-hcl-editor-path)
            assert_not_empty "$key" "$2"
            RIGHTSIZE_HCL_EDITOR_PATH="$2"
            shift
            ;;
        --skip-terragrunt-apply)
            assert_not_empty "$key" "$2"
            export IS_TERRAGRUNT_APPLY_SKIPPED="TRUE"
            shift
            ;;
        --help)
            print_usage
            exit
            ;;
        *)
            log_error "Unrecognized argument: $key"
            print_usage
            exit 1
            ;;
        esac
        shift
    done

    assert_is_option_used "'-c' or '--cluster'" "$AURORA_PROVISIONED_CLUSTER_NAME"
    assert_is_option_used "'-i' or '--instance-class'" "$AURORA_PROVISIONED_DESIRED_INSTANCE_CLASS"
    assert_is_option_used "'-r' or '--region'" "$AURORA_PROVISIONED_AWS_REGION"
    assert_is_option_used "'-p' or '--path'" "$AURORA_PROVISIONED_TERRAGRUNT_FULL_PATH"
    assert_is_option_used "'--account-id'" "$AURORA_PROVISIONED_AWS_ACCOUNT_ID"
    assert_is_option_used "'--rightsize-hcl-editor-path'" "$RIGHTSIZE_HCL_EDITOR_PATH"

    assert_is_installed "aws"
    assert_is_installed "grep"
    assert_is_installed "sed"
    assert_is_installed "git"
    assert_is_installed "date"

    assert_is_rightsize_hcl_editor_correct "$RIGHTSIZE_HCL_EDITOR_PATH"

    trap cleanup_on_failure EXIT

    run "$AURORA_PROVISIONED_CLUSTER_NAME" "$AURORA_PROVISIONED_DESIRED_INSTANCE_CLASS" "$AURORA_PROVISIONED_AWS_REGION" "$AURORA_PROVISIONED_AWS_ACCOUNT_ID" "$AURORA_PROVISIONED_TERRAGRUNT_FULL_PATH"
        
    trap - EXIT
    log_info "Rightsizing process completed successfully ✅"
}

main "$@"