#!/bin/bash
set -e

# Check condition
echo "Checking Kubecost Manifest File ..."
KUBECOST_MANIFEST_EXISTS=false
if [ -f kubecost.yaml ]; then
  KUBECOST_MANIFEST_EXISTS=true
fi

echo "Checking Kubecost Deployment ..."
KUBECOST_DEPLOYMENT_EXISTS=false
if kubectl get deploy kubecost-cost-analyzer -n kubecost > /dev/null 2>&1; then
  KUBECOST_DEPLOYMENT_EXISTS=true
fi

EXPECTED_KUBECOST_MANIFEST_EXISTS=true
EXPECTED_KUBECOST_DEPLOYMENT_EXISTS=true

emoji() {
  [[ "$1" == true ]] && echo "✅" || echo "❌"
}

# Print the result
printf "\n%-35s %-15s %-15s %s\n" "CHECK" "STATUS" "EXPECTED" "RESULT"
echo "----------------------------------------------------------------------------"

printf "%-35s %-15s %-15s %s\n" "KUBECOST MANIFEST EXISTS ?" "$KUBECOST_MANIFEST_EXISTS" "$EXPECTED_KUBECOST_MANIFEST_EXISTS" "$(emoji $KUBECOST_MANIFEST_EXISTS)"
printf "%-35s %-15s %-15s %s\n" "KUBECOST DEPLOYMENT EXISTS ?" "$KUBECOST_DEPLOYMENT_EXISTS" "$EXPECTED_KUBECOST_DEPLOYMENT_EXISTS" "$(emoji $KUBECOST_DEPLOYMENT_EXISTS)"