#!/bin/bash
echo "Chosen action: $ACTION"

# Namespaces to process
NAMESPACES=("otelcollector" "monitoring" "logging" "castai-agent")

# Set replicas based on action
if [[ "$ACTION" == "RESUME" ]]; then
  REPLICAS=1
elif [[ "$ACTION" == "PAUSE" ]]; then
  REPLICAS=0
else
  echo "❌ Invalid ACTION value: $ACTION"
  echo "Set ACTION to 'RESUME' or 'PAUSE'."
  exit 1
fi

scale_statefulsets_in_namespace() {
  local NAMESPACE="$1"
  echo "🔍 Checking StatefulSets in namespace '$NAMESPACE'..."

  STS=$(kubectl get statefulset -n "$NAMESPACE" -o jsonpath='{.items[*].metadata.name}')

  if [[ -z "$STS" ]]; then
    echo "❌ No StatefulSets found in namespace '$NAMESPACE'."
    return
  fi

  for sts in $STS; do
    echo "📏 Scaling StatefulSet '$sts' to $REPLICAS replicas in namespace '$NAMESPACE'..."
    kubectl scale statefulset "$sts" --replicas="$REPLICAS" -n "$NAMESPACE"
  done

  echo "✅ All StatefulSets in namespace '$NAMESPACE' have been scaled to $REPLICAS."
}

scale_deployments_in_namespace() {
  local NAMESPACE="$1"
  echo "🔍 Checking Deployments in namespace '$NAMESPACE'..."

  DEPLOY=$(kubectl get deployment -n "$NAMESPACE" -o jsonpath='{.items[*].metadata.name}')

  if [[ -z "$DEPLOY" ]]; then
    echo "❌ No Deployments found in namespace '$NAMESPACE'."
    return
  fi

  for deploy in $DEPLOY; do
    echo "📏 Scaling Deployment '$deploy' to $REPLICAS replicas in namespace '$NAMESPACE'..."
    kubectl scale deployment "$deploy" --replicas="$REPLICAS" -n "$NAMESPACE"
  done

  echo "✅ All Deployments in namespace '$NAMESPACE' have been scaled to $REPLICAS."
}

# Loop through namespaces
for ns in "${NAMESPACES[@]}"; do
  scale_statefulsets_in_namespace "$ns"
  scale_deployments_in_namespace "$ns"
done