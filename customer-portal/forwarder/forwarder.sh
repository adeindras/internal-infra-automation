#!/bin/bash

set -euo pipefail

main () {

  if [ "$#" -eq 1 ]; then 
    if [ "$1" == "-h" ]; then
      >&2 echo "Usage: forwarder.sh [<target_address> <target_port> <local_port>]"
      >&2 echo "    If no target is supplied, the script will give you a prompt to select a common target"
      exit 0
    fi
  fi

  # Try to get region from config
  # Check env var first, then aws config
  # Otherwise abort

  if [ -n "${AWS_DEFAULT_REGION:=}" ]; then
    REGION="${AWS_DEFAULT_REGION}"
  else
    set +e
    REGION="$(aws configure get region)"
    if [ -z  "${REGION}" ]; then
      >&2 echo "Please configure target region by using 'aws configure set region <region_name>', or by setting the 'AWS_DEFAULT_REGION' environment variable"
      exit 1
    fi
    set -e
  fi

  ACCOUNT="$(aws sts get-caller-identity --output text --query 'Account')"

  echo Choose target cluster:
  select envname in $(<environments.txt);
  do
    if [ -n "${envname}" ]; then
      echo "Loading parameters..."
      CLUSTER_NAME=$envname
      TASK_ARN=$(aws ecs list-tasks --cluster "${CLUSTER_NAME}" --service-name forwarder --query 'taskArns[0]' --output text)
      # Container 1 is the "service" container of the forwarder
      MANAGED_INSTANCE_ID=$(aws ecs describe-tasks --tasks "${TASK_ARN}" --cluster ${CLUSTER_NAME} --query "tasks[0].[taskArn, containers[?name=='service'] | [0].runtimeId] | join('_', @)" --output text | sed "s/arn:aws:ecs:${REGION}:${ACCOUNT}:task\//ecs:/g"| sed 's/\//_/g')
      break;
    fi
      >&2 echo "Invalid choice: ($REPLY)"
  done

  if [ "$#" -ne 3 ]; then
    echo Choose target to be forwarded:
    select target in rds elasticache documentdb;
    do
      if [ -n "${target}" ]; then
        echo "Forwarding to ${target} (${REPLY})"
        break;
      fi
        >&2 echo "Invalid choice: (${REPLY})"
    done


    if [ "${target}" == "rds" ]; then
      ADDRESS="$(aws ssm get-parameter --name="/ecs/${CLUSTER_NAME}/postgres_address" --with-decryption --output text --query 'Parameter.Value')"
      PORT="5432"
      LOCAL_PORT="15432"
    elif [ "${target}" == "elasticache" ]; then
      ADDRESS="$(aws ssm get-parameter --name="/ecs/${CLUSTER_NAME}/redis/redis_address" --with-decryption --output text --query 'Parameter.Value')"
      PORT="6379"
      LOCAL_PORT="16379"
    elif [ "${target}" == "documentdb" ]; then
      ADDRESS="$(aws ssm get-parameter --name="/ecs/${CLUSTER_NAME}/docdb_address" --with-decryption --output text --query 'Parameter.Value')"
      PORT="27017"
      LOCAL_PORT="27018"
    fi

  else
    ADDRESS="$1"
    PORT="$2"
    LOCAL_PORT="$3"
  fi

  echo "Region: ${REGION}"
  echo "ECS cluster: ${CLUSTER_NAME}"
  echo "Target address: ${ADDRESS}"
  echo "Target port: ${PORT}"
  echo "Local port: ${LOCAL_PORT}"

  aws ssm start-session --target "${MANAGED_INSTANCE_ID}" --document-name AWS-StartPortForwardingSessionToRemoteHost --parameters "{\"portNumber\":[\"${PORT}\"],\"localPortNumber\":[\"${LOCAL_PORT}\"],\"host\":[\"${ADDRESS}\"]}"
}

main "$@"
