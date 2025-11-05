#!/bin/bash

# Ensure jq is installed
if ! command -v jq &>/dev/null; then
  echo 'Error: jq is not installed.' >&2
  exit 1
fi

# Validate input argument
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <environment>" >&2
  exit 1
fi

environment=$1

echo -e "Checking if emissary-ingress-websocket exists..."
kubectl get kustomization -n flux-system -o jsonpath='{.metadata.name}:{range @.status.conditions[*]}{@.type}={@.status};{end}' emissary-ingress-websocket | grep -q "Ready=True"

echo -e "Checking if justice-websocket Ingress exists..."
kubectl get ingress -n emissary -o jsonpath='{.metadata.name}:{range @.status.loadBalancer.ingress[*]}ALB:{@.hostname};{end}' justice-websocket

# Start port-forwarding in the background
echo -e "Starting port-forward ..."
kubectl port-forward -n emissary svc/emissary-ingress-websocket-admin 8877:8877 >/dev/null 2>&1 &
PF_PID=$!
echo "Port-forward running with PID: $PF_PID"

# Cleanup function to stop the port-forward process on exit
cleanup() {
  echo "Stopping port-forward process: $PF_PID ..."
  kill $PF_PID
}
trap cleanup EXIT

sleep 5

echo "Downloading Emissary diagnostic info ..."
echo "Emissary route paths:"
curl -s "http://localhost:8877/ambassador/v0/diag/?json=true" | jq -r '.envoy_elements[] | select(.route[0]) | .route[] | {prefix: .match.prefix, cluster: .route.cluster} | .prefix'

echo "Downloading ALB rules info ..."
ALB_ARNs=$(aws elbv2 describe-load-balancers --query 'LoadBalancers[*].LoadBalancerArn' --output text)

ALB_RESOURCE_ARN=""
for arn in $ALB_ARNs; do  
  ALB_RESOURCE_ARN=$(aws elbv2 describe-tags --resource-arns "$arn" | jq -rce \
    "select(.TagDescriptions[].Tags[] == {\"Key\": \"elbv2.k8s.aws/cluster\", \"Value\": \"$environment\"}) | \
     select(.TagDescriptions[].Tags[] == {\"Key\":\"ingress.k8s.aws/stack\",\"Value\":\"ingress-controller\"}) | \
     .TagDescriptions[0].ResourceArn")
  [[ -n $ALB_RESOURCE_ARN ]] && break
done

if [[ -z $ALB_RESOURCE_ARN ]]; then
  echo "Error: No matching ALB found for environment '$environment'." >&2
  exit 1
fi

echo "ALB rule paths:"
aws elbv2 describe-listeners --load-balancer-arn "$ALB_RESOURCE_ARN" | jq -r '.Listeners[] | select(.Port == 443) | .ListenerArn' | \
  xargs -r aws elbv2 describe-rules --listener-arn | \
  jq -r '.Rules[] | select(.Actions[] | contains({"TargetGroupArn": "emissary"})) | .Conditions[] | select(.Field=="path-pattern").Values[0]'

echo -e "Make sure paths added in ALB and Emissary match."
exit 0