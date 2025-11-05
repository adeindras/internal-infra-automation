#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

COMMAND=$1

# Available Jenkins environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT}"
echo "Environment: ${ENVIRONMENT}"
echo "Environment Name: ${ENVIRONMENT_NAME}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"
echo "Working directory: ${WORKSPACE}"

ENVIRONMENT_MANIFEST_ROOT="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT}/${AWS_REGION}/${ENVIRONMENT_NAME}"
DEPLOYMENT_MANIFEST_ROOT="${WORKSPACE}/deployments/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT_NAME}"
echo "Environment manifest root directory: ${ENVIRONMENT_MANIFEST_ROOT}"
echo "Deployment manifest root directory: ${DEPLOYMENT_MANIFEST_ROOT}"

LOBBY_REPLICAS=""
ODIN_CONFIG_REPLICAS=""
MATCHMAKING_REPLICAS=""

function checkRedis {
  echo "--------------------------"
  echo "Checking Self-hosted Redis"
  echo "--------------------------"
  echo ""

  # Check if redis is running in the cluster

  if [[ ! $(kubectl wait -n redis --for=condition=Ready pod/redis-master-0) ]]; then
    echo "❌ Cannot find self-hosted Redis in the cluster"
    exit 1
  fi

  echo ""

  # Check if the SSM parameter exists
  redisAddress=$(aws ssm get-parameter --query='Parameter.Value' --name="/eks/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT_NAME}/redis/redis_address" --with-decryption 2>/dev/null || true)
  redisHost=$(aws ssm get-parameter --query='Parameter.Value' --name="/eks/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT_NAME}/redis/redis_host" --with-decryption 2>/dev/null || true )
  redisClusterHost=$(aws ssm get-parameter --query='Parameter.Value' --name="/eks/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT_NAME}/redis/redis_cluster_host" --with-decryption 2>/dev/null || true)

  params="{}"

  if [[  -n $redisAddress ]]; then
    echo "✅ redis_address exists: ${redisAddress}"
    params=$(jq ". + {redis_address: ${redisAddress}}" <<< "$params")
  else
    echo "❌ redis_address does not exist"
  fi

  if [[  -n $redisHost ]]; then
    echo "✅ redis_host parameter exists: ${redisHost}"
    params=$(jq ". + {redis_host: ${redisHost}}" <<< "$params")
  else
    echo "❌ redis_host does not exist"
  fi

  if [[ -n $redisClusterHost ]]; then
    echo "✅ redis_cluster_host parameter exists: ${redisClusterHost}"
    params=$(jq ". + {redis_cluster_host: ${redisClusterHost}}" <<< "$params")
  else
    echo "❌ redis_cluster_host does not exist"
  fi

  echo ""

  if [[ -z $redisAddress && -z $redisHost && -z $redisClusterHost ]]; then
    echo "❌ No SSM parameter found. Please check your AWS credentials."
    echo "Running aws sts get-caller-identity..."
    echo ""
    aws sts get-caller-identity
    exit 1
  fi

  echo "$params" > redis_params.json
}

function checkDeploymentManifests {
  # Check if the parameters are used in the deployments repository
  # Store the secret name so we can reconcile it later
  # Only support new CD
  #
  # Parameters:
  #   $1: json file containing parameters

  params=$(< "$1")

  echo "--------------------"
  echo "Checking Deployments"
  echo "--------------------"
  echo ""

  pushd "$DEPLOYMENT_MANIFEST_ROOT/services-overlay" > /dev/null
    secrets=""
    deployments=""

    filesWithRedisAddress=$(grep -rl 'redis_address' . || true)
    echo "${filesWithRedisAddress}"
    if [[ -n "$filesWithRedisAddress" ]]; then
      if [[ $(jq -r '.redis_address' <<< "$params") != "null" ]]; then
        echo "❕ redis_address exists, and there are deployments using redis_address"
        for filename in $filesWithRedisAddress; do
          secrets+=$(yq .metadata.name "$filename")$'\n'

          deploymentPath=$(sed 's#/secret.*\.yaml#/deployment.yaml#' <<< "$filename")
          if [[ -a "$deploymentPath" ]]; then
            deployments+="$(yq .metadata.name "$deploymentPath")"$'\n'
          fi

          deploymentWorkerPath=$(sed 's#/secret.*\.yaml#/deployment-worker.yaml#' <<< "$filename")
          if [[ -a "$deploymentWorkerPath" ]]; then
            deployments+=$(yq .metadata.name "$deploymentWorkerPath")$'\n'
          fi

          deploymentAPIPath=$(sed 's#/secret.*\.yaml#/deployment-api.yaml#' <<< "$filename")
          if [[ -a "$deploymentAPIPath" ]]; then
            deployments+=$(yq .metadata.name "$deploymentAPIPath")$'\n'
          fi
        done
      else
        echo "❌ redis_address doesn't exist, but there are deployments using redis_address."
        echo "Please check the following files:"
        echo "$filesWithRedisAddress"
      fi
    else
      echo "❕ No secret is using redis_address"
    fi

    echo ""

    filesWithRedisHost=$(grep -rl 'redis_host' .) || true
    if [[ -n "$filesWithRedisHost" ]]; then
      if [[ $(jq -r '.redis_host' <<< "$params") != "null" ]]; then
        echo "❕ redis_host exists, and there are deployments using redis_host"
        for filename in $filesWithRedisHost; do
          secrets+=$(yq .metadata.name "$filename")$'\n'

          deploymentPath=$(sed 's#/secret.*\.yaml#/deployment.yaml#' <<< "$filename")
          if [[ -a "$deploymentPath" ]]; then
            deployments+="$(yq .metadata.name "$deploymentPath")"$'\n'
          fi

          deploymentWorkerPath=$(sed 's#/secret.*\.yaml#/deployment-worker.yaml#' <<< "$filename")
          if [[ -a "$deploymentWorkerPath" ]]; then
            deployments+=$(yq .metadata.name "$deploymentWorkerPath")$'\n'
          fi

          deploymentAPIPath=$(sed 's#/secret.*\.yaml#/deployment-api.yaml#' <<< "$filename")
          if [[ -a "$deploymentAPIPath" ]]; then
            deployments+=$(yq .metadata.name "$deploymentAPIPath")$'\n'
          fi
        done
      else
        echo "❌ redis_host doesn't exist, but there are deployments using redis_host."
        echo "Please check the following files:"
        echo "$filesWithRedisHost"
      fi
    else
      echo "❕ No secret is using redis_host"
    fi

    echo ""

    filesWithRedisClusterHost=$(grep -rl 'redis_cluster_host' . || true)
    if [[ -n "$filesWithRedisClusterHost" ]]; then
      if [[ $(jq -r '.redis_host' <<< "$params") != "null" ]]; then
        echo "❕ redis_cluster_host exists, and there are deployments using redis_cluster_host"
        for filename in $filesWithRedisClusterHost; do
          secrets+=$(yq .metadata.name "$filename")$'\n'

          deploymentPath=$(sed 's#/secret.*\.yaml#/deployment.yaml#' <<< "$filename")
          if [[ -a "$deploymentPath" ]]; then
            deployments+="$(yq .metadata.name "$deploymentPath")"$'\n'
          fi

          deploymentWorkerPath=$(sed 's#/secret.*\.yaml#/deployment-worker.yaml#' <<< "$filename")
          if [[ -a "$deploymentWorkerPath" ]]; then
            deployments+=$(yq .metadata.name "$deploymentWorkerPath")$'\n'
          fi

          deploymentAPIPath=$(sed 's#/secret.*\.yaml#/deployment-api.yaml#' <<< "$filename")
          if [[ -a "$deploymentAPIPath" ]]; then
            deployments+=$(yq .metadata.name "$deploymentAPIPath")$'\n'
          fi
        done
      else
        echo "❌ redis_cluster_host doesn't exist, but there are deployments using redis_cluster_host."
        echo "Please check the following files:"
        echo "$filesWithRedisClusterHost"
      fi
    else
      echo "❕ No secret is using redis_cluster_host"
    fi
  popd > /dev/null

  echo ""
  echo "---"
  echo "Secrets to be synced:"
  tee <<< "$secrets" service-secrets
  echo ""
  echo "Deployments to be restarted:"
  tee <<< "$deployments" deployments
}

function generateSelfhostedValkey() {
  cp valkey_template.yaml "$ENVIRONMENT_MANIFEST_ROOT/sync/extended/valkey.yaml"

  pushd "$ENVIRONMENT_MANIFEST_ROOT/sync/extended" > /dev/null
  yq -i '.bases += "./valkey.yaml"' kustomization.yaml
  popd > /dev/null

  cp -r "$ENVIRONMENT_MANIFEST_ROOT/rbac/namespaces/redis"  "$ENVIRONMENT_MANIFEST_ROOT/rbac/namespaces/valkey"

  pushd "$ENVIRONMENT_MANIFEST_ROOT/rbac/namespaces/" > /dev/null

  yq -i '.bases += "./valkey"' kustomization.yaml

  pushd "valkey" > /dev/null
  yq -i '.metadata.name = "valkey"' namespace.yaml
  yq -i '.namespace = "valkey"' kustomization.yaml
  yq -i '.namespace = "valkey"' roles/kustomization.yaml
  popd > /dev/null

  popd > /dev/null

  git diff
}

function reconcileValkey() {
  flux resume source git iac-repo
  flux reconcile source git iac-repo
  flux reconcile kustomization namespaces
  flux resume kustomization cluster-variables
  flux reconcile kustomization cluster-variables
  flux resume kustomization flux-system
  flux resume source git flux-system
  flux reconcile kustomization flux-system --with-source
  flux reconcile kustomization flux-volume || true # flux-volume might not exist
  flux reconcile kustomization flux
  flux reconcile kustomization valkey-justice
  flux -n valkey reconcile helmrepository bitnami-valkey-justice
  flux -n valkey reconcile helmrelease valkey-justice
}

function enableReplication() {
  kubectl wait -n valkey --for=condition=Ready pod valkey-justice-primary-0
  kubectl exec -n valkey valkey-justice-primary-0 -- valkey-cli REPLICAOF redis-master.redis 6379
}

function checkReplication() {
  slaveOffset=$(kubectl exec -n redis redis-master-0 -- redis-cli INFO REPLICATION | grep 'slave0:' | sed 's/.*offset=\([0-9]\+\),.*$/\1/')
  masterOffset=$(kubectl exec -n redis redis-master-0 -- redis-cli INFO REPLICATION | grep 'master_repl_offset' | sed 's/master_repl_offset:\([0-9]\+\).*$/\1/')

  while [[ $masterOffset != $slaveOffset ]]; do
    echo "Offset mismatch: Valkey:$slaveOffset,Redis:$masterOffset"
    echo "Waiting..."

    slaveOffset=$(kubectl exec -n redis redis-master-0 -- redis-cli INFO REPLICATION | grep 'slave0:' | sed 's/.*offset=\([0-9]\+\),.*$/\1/')
    masterOffset=$(kubectl exec -n redis redis-master-0 -- redis-cli INFO REPLICATION | grep 'master_repl_offset' | sed 's/master_repl_offset:\([0-9]\+\).*$/\1/')
    echo "New offset: Valkey:$slaveOffset,Redis:$masterOffset"
  done

  echo "Redis (primary) and Valkey (replica) are in sync!"
  echo "Valkey (replica) offset: $slaveOffset"
  echo "Redis (primary) offset: $masterOffset"
}

function updateSSM() {
  # Parameters:
  #   $1: json file containing parameters

  params=$(< "$1")
  ssmKeys=$(jq -r 'keys | .[]' <<< "$params")

  for ssmKey in $ssmKeys; do
    echo "Updating /eks/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT_NAME}/redis/${ssmKey}..."
    aws ssm put-parameter --name="/eks/${CUSTOMER_NAME}/${PROJECT}/${ENVIRONMENT_NAME}/redis/${ssmKey}" --overwrite --type=SecureString --value=valkey-justice-primary.valkey
  done
}

function syncSecret() {
  # Parameters:
  #   $1: file containing list of secrets

  secrets=$(< "$1")

  for secret in $secrets; do
    kubectl annotate es -n justice "${secret}" force-sync=$(date +%s) --overwrite || true
  done
}

function restartDeployment() {
  # Parameters:
  #   $1: file containing list of deployments

  deployments=$(< "$1")

  for deployment in $deployments; do
    kubectl rollout restart deployment -n justice "${deployment}" || true
  done

  # Some deployments are special, and can only connect to primary.

  LOBBY_REPLICAS=$(kubectl get deploy -n justice justice-lobby-server -o jsonpath='{$.spec.replicas}' || true)
  ODIN_CONFIG_REPLICAS=$(kubectl get deploy -n justice justice-odin-config-service -o jsonpath='{$.spec.replicas}' || true)
  MATCHMAKING_REPLICAS=$(kubectl get deploy -n justice justice-matchmaking -o jsonpath='{$.spec.replicas}' || true)

  echo $LOBBY_REPLICAS > lobby_replicas
  echo $ODIN_CONFIG_REPLICAS > odin_config_replicas
  echo $MATCHMAKING_REPLICAS > matchmaking_replicas

  if [[ -n $LOBBY_REPLICAS ]]; then
    echo "lobby replica: $LOBBY_REPLICAS"
    kubectl scale deploy -n justice justice-lobby-server --replicas=0
  fi

  if [[ -n $ODIN_CONFIG_REPLICAS ]]; then
    echo "odin config replica: $ODIN_CONFIG_REPLICAS"
    kubectl scale deploy -n justice justice-odin-config-service --replicas=0
  fi

  if [[ -n $MATCHMAKING_REPLICAS ]]; then
    echo "matchmaking replica: $MATCHMAKING_REPLICAS"
    kubectl scale deploy -n justice justice-matchmaking --replicas=0
  fi

}

function disableReplication() {
  kubectl exec -n valkey valkey-justice-primary-0 -- valkey-cli REPLICAOF NO ONE

  LOBBY_REPLICAS=$(<lobby_replicas)
  ODIN_CONFIG_REPLICAS=$(<odin_config_replicas)
  MATCHMAKING_REPLICAS=$(<matchmaking_replicas)

  # Scale special deployments out after replication is disabled
  if [[ -n $LOBBY_REPLICAS ]]; then
    echo "lobby replica: $LOBBY_REPLICAS"
    kubectl scale deploy -n justice justice-lobby-server --replicas=${LOBBY_REPLICAS}
  fi

  if [[ -n $ODIN_CONFIG_REPLICAS ]]; then
    echo "odin config replica: $ODIN_CONFIG_REPLICAS"
    kubectl scale deploy -n justice justice-odin-config-service --replicas=${ODIN_CONFIG_REPLICAS}
  fi

  if [[ -n $MATCHMAKING_REPLICAS ]]; then
    echo "matchmaking replica: $MATCHMAKING_REPLICAS"
    kubectl scale deploy -n justice justice-matchmaking --replicas=${MATCHMAKING_REPLICAS}
  fi
}

function scaleDownRedis() {
  kubectl scale sts redis-master -n redis --replicas=0
}

case "$COMMAND" in
  check-redis)
    checkRedis;;
  check-deployment)
    checkDeploymentManifests "redis_params.json";;
  generate-valkey)
    generateSelfhostedValkey;;
  reconcile-valkey)
    reconcileValkey;;
  enable-replication)
    enableReplication;;
  check-replication)
    checkReplication;;
  update-ssm)
    updateSSM "redis_params.json";;
  sync-secret)
    syncSecret "service-secrets";;
  restart-deployment)
    restartDeployment "deployments";;
  disable-replication)
    disableReplication;;
  scaledown-redis)
    scaleDownRedis;;
  *)
    echo "Unknown command"
    exit 1
    ;;
esac
