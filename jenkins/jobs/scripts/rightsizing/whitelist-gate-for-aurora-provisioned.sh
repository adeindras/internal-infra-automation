#!/usr/bin/env bash

# This script acts as a security gate for Terraform/Terragrunt plans.
# It validates a plan against a strict, operation-aware whitelist defined in an
# external JSON file to ensure only pre-approved changes are automated.

set -eo pipefail

# --- Script Constants ---
PLAN_FILE="tgplan.bin"
JSON_PLAN_FILE="plan.json"
SCRIPT_NAME="$(basename "$0")"

# --- Helper Functions ---
# Checks if a given item exists in a jq array.
# Usage: is_item_in_list "item" '["item1", "item2"]'
is_item_in_list() {
    local item="$1"
    local json_array="$2"
    if echo "$json_array" | jq -e --arg item "$item" '.[] | select(. == $item)' > /dev/null; then
        return 0 # Success (true)
    else
        return 1 # Failure (false)
    fi
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

assert_is_installed() {
    local -r name="$1"
    if [[ ! $(command -v "${name}") ]]; then
        log_error "The binary '$name' is required by this script but is not installed or in the system's PATH."
        exit 1
    fi
}

# --- Main Logic ---
main() {
    assert_is_installed "terragrunt"
    assert_is_installed "grep"
    assert_is_installed "jq"
    assert_is_installed "sed"

    # --- Configuration ---
    # The whitelist is now defined in an external JSON file for better maintainability.
    WHITELIST_FILE="$1"

    if [[ -z "$WHITELIST_FILE" ]]; then
        log_error "'\$WHITELIST_FILE' is empty."
        exit 1
    fi

    if [[ ! -f "$WHITELIST_FILE" ]]; then
        log_error "Whitelist file not found at '$WHITELIST_FILE'."
        exit 1
    fi

    log_info "Generating plan..."
    terragrunt plan -out=${PLAN_FILE} --terragrunt-log-level error > /dev/null

    log_info "Converting plan to JSON..."
    terragrunt show -json ${PLAN_FILE} --terragrunt-log-level error --terragrunt-no-color | sed -n '/^{/,$p' | jq -s '.[-1]' > ${JSON_PLAN_FILE} || true

    if ! grep -q '[^[:space:]]' "$JSON_PLAN_FILE"; then
        log_info "✅ Validation successful (no changes detected)."
        log_info "RESULT=NO_CHANGES"
        exit 0
    fi

    log_info "Validating plan against whitelist policy from '$WHITELIST_FILE'..."
    all_changes_valid=true

    local addresses_to_check
    addresses_to_check=$(jq -r '
        .resource_changes[]
        | select(.change.actions[] | (. != "read" and . != "no-op"))
        | .address
    ' "${JSON_PLAN_FILE}")

    if [[ -z "$addresses_to_check" ]]; then
        log_info "✅ Validation successful (plan contains no actionable resource changes)."
        log_info "RESULT=NO_CHANGES"
        exit 0
    fi

    while IFS= read -r address; do
        local change_object
        change_object=$(jq -r --arg addr "$address" '.resource_changes[] | select(.address == $addr)' "${JSON_PLAN_FILE}")

        local type
        type=$(echo "$change_object" | jq -r '.type')
        
        local action
        action=$(echo "$change_object" | jq -r '.change.actions[] | select(. != "read" and . != "no-op")')

        log_info "--> Validating change for: $address (Action: $action, Type: $type)"

        # Check if the resource type and action key exist in the whitelist JSON.
        if ! jq -e --arg type "$type" --arg action "$action" '.[$type] and (.[$type] | has($action))' "$WHITELIST_FILE" > /dev/null; then
            log_info "    ❌ FAILED: Resource type '$type' with action '$action' is not defined in the whitelist."
            all_changes_valid=false
            continue # Move to the next resource change
        fi

        # **FIX APPLIED HERE**: Add specific logic for each action type.
        if [[ "$action" == "update" ]]; then
            # For "update", we must validate every changing attribute.
            local allowed_attrs_json
            allowed_attrs_json=$(jq -r --arg type "$type" '.[$type].update' "$WHITELIST_FILE")
            local is_attrs_valid=true

            local changed_attrs
            changed_attrs=$(echo "$change_object" | jq -r '
                ( .change.after // {} ) as $after |
                ( .change.before // {} ) as $before |
                ( ($before | keys_unsorted) + ($after | keys_unsorted) ) | unique[] |
                select( ($before[.]) != ($after[.]) )
            ')

            for attr in $changed_attrs; do
                if ! is_item_in_list "$attr" "$allowed_attrs_json"; then
                    log_info "    ❌ FAILED: Attribute '$attr' is not whitelisted for update."
                    is_attrs_valid=false
                    all_changes_valid=false
                fi
            done

            if [[ "$is_attrs_valid" == "true" ]]; then
                log_info "    ✅ PASSED: All attribute updates are whitelisted."
            fi
        elif [[ "$action" == "create" ]] || [[ "$action" == "delete" ]]; then
            # For "create" and "delete", an empty array `[]` means the action is explicitly disallowed.
            local allowed_list
            allowed_list=$(jq -r --arg type "$type" --arg action "$action" '.[$type][$action]' "$WHITELIST_FILE")
            if [[ "$allowed_list" == "[]" ]]; then
                log_info "    ❌ FAILED: Action '$action' is explicitly disallowed for '$type' (empty list in whitelist)."
                all_changes_valid=false
            else
                log_info "    ✅ PASSED: '$action' operation is whitelisted for '$type'."
            fi
        fi
    done <<< "$addresses_to_check"

    # --- Final Result ---
    if [[ "$all_changes_valid" == "false" ]]; then
        log_info "Plan validation failed. One or more changes are not on the approved whitelist."
        log_info "RESULT=ABORT"
        exit 1
    fi

    log_info "✅ Validation successful. All changes are on the approved whitelist."

    # Now, determine which workflow to trigger next.
    if jq -e '.resource_changes[] | select(.change.after.instance_class != .change.before.instance_class)' "${JSON_PLAN_FILE}" > /dev/null; then
        log_info "'instance_class' change detected."
        log_info "RESULT=PROCEED_WITH_INSTANCE_CLASS_ROLLOUT_WORKFLOW"
    else
        log_info "Only safe, online changes detected."
        log_info "RESULT=PROCEED_WITH_SIMPLE_APPLY"
    fi
}

# --- Script Entrypoint ---
main "$@"

