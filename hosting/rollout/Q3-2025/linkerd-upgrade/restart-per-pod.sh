#!/bin/sh

set -e

echo "📥 Fetching pods, ReplicaSets, StatefulSets..."

# Fetch once
kubectl get rs -A -o json > /tmp/rs.json
kubectl get pod -A -o json > /tmp/pods.json

# Create mapping file: <namespace> <replicaset> <deployment>
echo "🔧 Building ReplicaSet -> Deployment map..."
jq -r '
  .items[]
  | select(.metadata.ownerReferences[0].kind == "Deployment")
  | [.metadata.namespace, .metadata.name, .metadata.ownerReferences[0].name]
  | @tsv
' /tmp/rs.json > /tmp/rs_map.txt

# Track restarted deployments
> /tmp/restarted.txt

# Process injected pods
echo "🔍 Looking for linkerd-injected pods..."
jq -r '
  .items[]
  | select(.spec.containers[].name == "linkerd-proxy")
  | [.metadata.namespace, .metadata.name, .metadata.ownerReferences[0].kind, .metadata.ownerReferences[0].name]
  | @tsv
' /tmp/pods.json |
while IFS=$'\t' read ns pod ownerKind ownerName; do

  case "$ns" in
    *ext-*)
      echo "⏭️  Skipping pod $pod in namespace $ns (contains 'ext-')"
      continue
      ;;
  esac
  
  if [ "$ownerKind" = "ReplicaSet" ]; then
    deploy=$(grep -F "$ns	$ownerName" /tmp/rs_map.txt | awk '{print $3}')
    if [ -n "$deploy" ]; then
      if ! grep -q "$ns/$deploy" /tmp/restarted.txt; then
        echo "🔁 Restarting deployment $deploy in namespace $ns"
        kubectl rollout restart deployment "$deploy" -n "$ns"
        echo "$ns/$deploy" >> /tmp/restarted.txt
      fi
    else
      echo "⚠️  Could not map ReplicaSet $ownerName in $ns to a Deployment"
    fi
  elif [ "$ownerKind" = "StatefulSet" ]; then
    echo "❌ Deleting pod $pod (StatefulSet) in $ns"
    kubectl delete pod "$pod" -n "$ns"
  else
    echo "⚠️  Skipping pod $pod in $ns (controller: $ownerKind)"
  fi
done
