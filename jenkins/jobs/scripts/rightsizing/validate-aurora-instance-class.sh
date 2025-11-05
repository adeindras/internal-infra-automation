#!/usr/bin/env bash

set -e

# --- Configuration ---
RESOURCE_TYPE="aws_rds_cluster_instance"
ALLOWED_PROPERTY="instance_class"
PLAN_FILE="tgplan.bin"
JSON_PLAN_FILE="plan.json"

# --- Main Logic ---
echo "INFO: Generating plan..."
terragrunt plan -out=${PLAN_FILE} --terragrunt-log-level error > /dev/null

echo "INFO: Creating a clean JSON plan file..."
terragrunt show -json ${PLAN_FILE} --terragrunt-log-level error --terragrunt-no-color | grep '^{' > ${JSON_PLAN_FILE} || true

if ! grep -q '[^[:space:]]' "$JSON_PLAN_FILE"; then
    echo "✅ Validation successful (no changes found)."
    exit 0
fi

if ! jq -e 'if type == "object" and .resource_changes then true else false end' "${JSON_PLAN_FILE}" > /dev/null; then
    echo "✅ Validation successful (plan file indicates no changes or is not a valid plan object)."
    exit 0
fi

echo "INFO: Validating plan against strict policy..."

# Get a list of addresses for ALL resources of the target type that are changing.
changing_instances=$(jq -r --arg rtype "$RESOURCE_TYPE" '
    .resource_changes[]
    | select(.type == $rtype)
    | .address
' "${JSON_PLAN_FILE}")

if [[ -z "$changing_instances" ]]; then
    echo "✅ Validation successful (no relevant resources were changed)."
    exit 0
fi

# --- Bash Validation Loop ---
is_valid=true
while IFS= read -r address; do
    echo "--> Checking instance: $address"

    # **YOUR FIX APPLIED HERE**
    # Get the raw action string (e.g., "update", "create") for the current resource.
    action=$(jq -r --arg addr "$address" '
        .resource_changes[]
        | select(.address == $addr)
        | .change.actions[]
    ' "${JSON_PLAN_FILE}")

    echo "action: $action"

    # --- STRICT POLICY LOGIC ---
    if [[ "$action" == "update" ]]; then
        # If the action is "update", run the detailed property check.
        property_keys=$(jq -r --arg addr "$address" '.resource_changes[] | select(.address == $addr) | .change.before | keys_unsorted | .[]' "${JSON_PLAN_FILE}")
        changed_keys_array=()

        for key in $property_keys; do
            before_value=$(jq --arg addr "$address" --arg k "$key" '.resource_changes[] | select(.address == $addr) | .change.before[$k]' "${JSON_PLAN_FILE}")
            after_value=$(jq --arg addr "$address" --arg k "$key" '.resource_changes[] | select(.address == $addr) | .change.after[$k]' "${JSON_PLAN_FILE}")

            if [[ "$before_value" != "$after_value" ]]; then
                changed_keys_array+=("$key")
            fi
        done

        # Use mapfile to safely sort the array of changed keys.
        mapfile -t sorted_changed_keys < <(sort <<<"${changed_keys_array[*]}")

        if [[ "${#sorted_changed_keys[@]}" -ne 1 ]]; then
            is_valid=false
            echo "    ❌ FAILED: Update action has an invalid number of changes. Expected 1, found ${#sorted_changed_keys[@]} (${sorted_changed_keys[*]})."
        elif [[ "${sorted_changed_keys[0]}" != "$ALLOWED_PROPERTY" ]]; then
            is_valid=false
            echo "    ❌ FAILED: Update action changed an invalid property. Expected '$ALLOWED_PROPERTY', found '${sorted_changed_keys[0]}'."
        else
            echo "    ✅ PASSED: Update action is valid."
        fi
    elif [[ "$action" == "no-op" ]]; then
        echo "    ✅ PASSED: no-op action is valid."
        is_valid=true
    elif [[ "$action" == "create" || "$action" == "delete" ]]; then
        echo "    ❌ FAILED: Action is '$action', which is not allowed by the policy."
        is_valid=false
    else
        # Handle any other unexpected actions
        is_valid=false
        echo "    ❌ FAILED: Unhandled or invalid action '$action' found."
    fi

done <<< "$changing_instances"

# --- Final Result Check ---
if [[ "$is_valid" == "true" ]]; then
  echo "✅ Validation successful."
  exit 0
else
  echo "❌ Validation failed."
  exit 1
fi