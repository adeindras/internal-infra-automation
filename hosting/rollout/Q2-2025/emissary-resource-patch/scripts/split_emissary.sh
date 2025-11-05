#!/bin/bash

set -euo pipefail

# Define environment variables
environment_deployment_root="${WORKSPACE}/deployments/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}"

function check_binaries() {
    binaries_exec_check=true

    list_req_binaries="yq kubectl"
    echo "Checking required binaries ..."
    for i in ${list_req_binaries}; do 
        if ! command -v ${i} > /dev/null; then
            echo \"${i}\" not found.
            binaries_exec_check=false
        fi
    done

    if [[ $binaries_exec_check != "true" ]]; then
        echo "Operation aborted. Please install required missing binaries."
        exit 1
    fi
}

check_binaries

old_cd_folder="${environment_deployment_root}/services/emissary-ingress"
new_cd_folder="${environment_deployment_root}/services-overlay/emissary-ingress"

if [[ -d "$new_cd_folder" ]]; then
    echo "[+] services-overlay/emissary-ingress exists, executing patch_mapping.sh ..."
    bash ./scripts/patch_mapping.sh
elif [[ -d "$old_cd_folder" ]]; then
    echo "[+] services/emissary-ingress exists, executing patch_mapping_old.sh ..."
    bash ./scripts/patch_mapping_old.sh
else
    echo "[ERROR] Neither ${old_cd_folder} nor ${new_cd_folder} exists. Exiting ..."
    exit 1
fi