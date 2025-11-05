#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

# Available environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "Environment Name: ${ENVIRONMENT_NAME}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Workspace: ${WORKSPACE}"

versionErrorExit () {
  echo "${2} is not newer than ${1}"
  exit 1
}

# Check Flux version in the cluster, compare it with the target version
#   If missing > 0.37, continue
#   If current version > target: abort
targetVersion=$1
targetMajorVersion=$(awk -F"." '{print $1}' <<< ${targetVersion})
targetMinorVersion=$(awk -F"." '{print $2}' <<< ${targetVersion})
targetPatchVersion=$(awk -F"." '{print $3}' <<< ${targetVersion})


currentVersion=$(kubectl get deploy -n flux-system kustomize-controller -o jsonpath="{@.metadata.labels.app\.kubernetes\.io/version}") 
currentVersion=${currentVersion:1}

if [[ -z ${currentVersion} ]]; then
  currentVersion="0.37.0"
fi

echo "current Flux version: ${currentVersion}"
currentMajorVersion=$(awk -F"." '{print $1}' <<< ${currentVersion})
currentMinorVersion=$(awk -F"." '{print $2}' <<< ${currentVersion})
currentPatchVersion=$(awk -F"." '{print $3}' <<< ${currentVersion})

if [[ currentMajorVersion -gt targetMajorVersion ]]; then
  versionErrorExit $currentVersion $targetVersion
elif [[ currentMajorVersion -eq targetMajorVersion ]]; then
  if [[ currentMinorVersion -gt targetMinorVersion ]]; then
    versionErrorExit $currentVersion $targetVersion
  elif [[ currentMinorVersion -eq targetMinorVersion ]]; then 
    if [[ currentPatchVersion -gt targetPatchVersion ]]; then
      versionErrorExit $currentVersion $targetVersion
    elif [[ currentMinorVersion -eq targetMinorVersion ]]; then 
      echo "target version matches current version (${currentVersion}). Will continue with the checks"
    fi
  fi
fi

if [[ ! -e "${currentVersion}" || ! -f "${currentVersion}/flux" ]]; then
  mkdir "${currentVersion}"
  curl -L -o - https://github.com/fluxcd/flux2/releases/download/v${currentVersion}/flux_${currentVersion}_linux_amd64.tar.gz | tar -C ${currentVersion} -xz
fi


./${currentVersion}/flux check
# Run flux check
#   If fail: abort

# Print controller error to file to be archived
./${currentVersion}/flux logs --all-namespaces --level=error > controller-error.log
# Print all Kustomization to file to be archived

# Print all sources to file to be archived
./${currentVersion}/flux get sources all -A > sources.log
./${currentVersion}/flux get kustomizations -A > kustomizations.log
./${currentVersion}/flux get helmreleases -A > helmreleases.log

# Print all warning events to be archived
kubectl get events -n flux-system --field-selector type=Warning > controller-warning-events.log
