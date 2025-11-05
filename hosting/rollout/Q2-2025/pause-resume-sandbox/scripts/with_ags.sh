#!/bin/bash

echo "Chosen action: $ACTION"

if [[ "$ACTION" == "RESUME" ]]; then

  echo "Syncing the secret..."

  for SECRET in $(kubectl get es -n justice -o jsonpath="{.items[*].metadata.name}" | tr ' ' '\n' | grep "^justice-.*-secret$" | grep -v ".*-glues-secret$"); do
    echo "Annotating $SECRET with force-sync=$(date +%s)"
    kubectl annotate -n justice es "$SECRET" force-sync="$(date +%s)" --overwrite
  done

  echo "Scaling up AGS services..."

  kubectl scale deploy -n justice justice-iam-service --replicas=1 --timeout=10m

  kubectl scale deploy -n justice --all --replicas=1

elif [[ "$ACTION" == "PAUSE" ]]; then
  echo "Scaling down AGS services..."

  kubectl scale deploy -n justice --all --replicas=0

else
  echo "Unknown action: $ACTION"
  exit 1
fi
