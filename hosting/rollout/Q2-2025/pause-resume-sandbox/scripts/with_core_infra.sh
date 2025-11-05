#!/bin/bash

echo "Chosen action: $ACTION"

if [[ "$ACTION" == "RESUME" ]]; then
  echo "Scaling up core infra..."

  # Scale deployments except aws-load-balancer-controller
  kubectl scale deployment -n flux-system --all --replicas=1
  kubectl get deployments -n kube-system -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
    | grep -v '^aws-load-balancer-controller$' \
    | xargs -I{} kubectl scale deployment {} -n kube-system --replicas=1

  kubectl scale deployment -n karpenter --all --replicas=1
  kubectl scale deployment -n linkerd --all --replicas=1
  # kubectl scale deployment -n linkerd-viz --all --replicas=1
  # kubectl scale deployment -n linkerd-jaeger --all --replicas=1

  echo "Waiting the core infra services ready..."
  echo "Sleep for 60s..."
  sleep 60

elif [[ "$ACTION" == "PAUSE" ]]; then
  echo "Scaling down core infra..."

  # Scale deployments except aws-load-balancer-controller
  kubectl scale deployment -n flux-system --all --replicas=0
  kubectl get deployments -n kube-system -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
    | grep -v -E '^(aws-load-balancer-controller|coredns)$' \
    | xargs -I{} kubectl scale deployment {} -n kube-system --replicas=0

  kubectl scale deployment -n linkerd --all --replicas=0
  # kubectl scale deployment -n linkerd-viz --all --replicas=0
  # kubectl scale deployment -n linkerd-jaeger --all --replicas=0

  echo "Draining nodes..."

  for val in critical-workload default; do
    kubectl get nodes -l karpenter.k8s.aws/ec2nodeclass=$val -o name | while read -r node; do
      echo "Draining $node..."
      kubectl drain "$node" --delete-emptydir-data --ignore-daemonsets --force
    done
  done

  echo "Waiting 60s before verifying..."
  sleep 60

  echo "Verifying nodes termination..."

  retries=5
  interval=60  # seconds
  while (( retries > 0 )); do
    existing_nodes=$(kubectl get nodes -l 'karpenter.k8s.aws/ec2nodeclass in (critical-workload,default)' -o name)
    if [[ -z "$existing_nodes" ]]; then
      echo "All critical and default nodes removed."
      break
    fi

    echo "Nodes still exist. Re-draining..."
    for node in $existing_nodes; do
      echo "Re-draining $node..."
      kubectl drain "$node" --delete-emptydir-data --ignore-daemonsets --force
    done

    echo "Sleeping for $interval seconds before rechecking..."
    sleep $interval
    ((retries--))
  done

  if (( retries == 0 )); then
    echo "Warning: Some nodes are still not terminated after multiple attempts."
    echo "Attempting to forcefully remove finalizers..."

    for node in $existing_nodes; do
      echo "Patching finalizer for $node..."
      kubectl patch node "$node" --type=json -p='[{"op": "remove", "path": "/metadata/finalizers/0"}]'

      echo "Sleeping for $interval seconds..."
      sleep $interval
    done
  fi

  echo "Scaling down karpenter..."
  kubectl scale deployment -n karpenter --all --replicas=0

else
  echo "Unknown action: $ACTION"
  exit 1
fi
