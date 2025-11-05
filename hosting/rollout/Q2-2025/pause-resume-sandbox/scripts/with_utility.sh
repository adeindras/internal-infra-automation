#!/bin/bash

echo "Chosen action: $ACTION"

if [[ "$ACTION" == "RESUME" ]]; then
  echo "Scaling up utility services..."

  kubectl scale deploy -n default --all --replicas=1
  kubectl scale deploy -n tools --all --replicas=1


elif [[ "$ACTION" == "PAUSE" ]]; then
  echo "Scaling down utility services..."

  kubectl scale deploy -n default --all --replicas=0
  kubectl scale deploy -n tools --all --replicas=0

else
  echo "Unknown action: $ACTION"
  exit 1
fi
