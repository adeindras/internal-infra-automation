#!/bin/bash

echo "Chosen action: $ACTION"

if [[ "$ACTION" == "RESUME" ]]; then
  echo "Scaling up ingress services..."
  
  kubectl scale deployment -n kube-system aws-load-balancer-controller --replicas=1

  kubectl scale deployment -n emissary --all --replicas=1
  kubectl scale deployment -n emissary-system --all --replicas=1

  echo "Waiting the emissary services ready..."
  echo "Sleep for 60s..."
  sleep 60

elif [[ "$ACTION" == "PAUSE" ]]; then
  echo "Deleting ALB..."

  kubectl scale deployment -n kube-system aws-load-balancer-controller --replicas=0

  aws elbv2 describe-load-balancers --region "${REGION_NAME}" \
    --query "LoadBalancers[*].LoadBalancerArn" --output text | tr '\t' '\n' | while read -r alb_arn; do
    tag_value=$(aws elbv2 describe-tags --resource-arns "$alb_arn" --region "${REGION_NAME}" \
      --query "TagDescriptions[0].Tags[?Key=='elbv2.k8s.aws/cluster'].Value" --output text)
    if [ "$tag_value" = "${CLUSTER_NAME}" ]; then
      echo "Deleting $alb_arn"
      aws elbv2 delete-load-balancer --load-balancer-arn "$alb_arn" --region "${REGION_NAME}"
    fi
  done

  echo "Scaling down ingress services..."

  kubectl scale deployment -n emissary --all --replicas=0
  kubectl scale deployment -n emissary-system --all --replicas=0

else
  echo "Unknown action: $ACTION"
  exit 1
fi