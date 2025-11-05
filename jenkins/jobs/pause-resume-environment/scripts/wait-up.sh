#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

wait_for_pods_to_exist() {
  local ns=$1
  local pod_name_prefix=$2
  local max_wait_secs=$3
  local interval_secs=2
  local start_time
  start_time=$(date +%s)
  echo "Waiting for the pod with name prefix \"$pod_name_prefix\" in \"$ns\" namespace to be exist..."
  while true; do
    
    current_time=$(date +%s)
    if (( (current_time - start_time) > max_wait_secs )); then
      echo "Waited for pods in namespace \"$ns\" with name prefix \"$pod_name_prefix\" to exist for $max_wait_secs seconds without luck. Returning with error."
      return 1
    fi

    if kubectl -n "$ns" describe pod "$pod_name_prefix" --request-timeout "5s"  &> /dev/null; then
      echo "Pods in namespace \"$ns\" with name prefix \"$pod_name_prefix\" exist."
      break
    else
      sleep $interval_secs
    fi
  done
}

source .env
set -e
while getopts n:w:k:t:s: flag
do
    case "${flag}" in
        n) namespace=${OPTARG};;
        w) workloadname=${OPTARG};;
        k) workloadkind=${OPTARG};;
        t) timeout=${OPTARG};;
        s) podkeyword=${OPTARG};;
    esac
done


wait_for_pods_to_exist $namespace $podkeyword $timeout
kubectl -n $namespace rollout status --watch --timeout=3600s $workloadkind/$workloadname
if [ "$workloadname" == "nomad-server" ]; then
  echo "Creating nomad initial jobs..."
  kubectl create job --from cronjob/consul-server-ssm-put-tls consul-ssm-put-tls-manual -n justice-play
  kubectl create job --from cronjob/nomad-server-ssmps-helpers-scripts nomad-ssm-put-tls-manual -n justice-play
fi