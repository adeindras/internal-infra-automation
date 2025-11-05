#!/bin/bash

set -euo pipefail

echo "🔍 Finding namespaces with Linkerd mesh or injection enabled..."

inject_ns=$(kubectl get ns -l linkerd.io/inject=enabled -o jsonpath='{.items[*].metadata.name}')
proxy_ns=$(kubectl get pods -A -o json | jq -r '
    .items[] 
    | select(.spec.containers[].name == "linkerd-proxy")
    | .metadata.namespace
')
all_ns=$(echo -e "${inject_ns}\\n${proxy_ns}" | sort -u)

echo "📋 Namespaces with Linkerd mesh or injection: "
echo "$all_ns"

echo "🔎 Filtering namespaces that start with 'ext-'..."
filtered_ns=$(echo "$all_ns" | grep '^ext-|prod|justice')

if [ -z "$filtered_ns" ]; then
    echo "⚠️  No ext-* meshed namespaces found."
    exit 0
fi

echo "🔁 Restarting deployments in the following namespaces:"
echo "$filtered_ns"

for ns in $filtered_ns; do
    echo "➡️  Restarting deployments in namespace: $ns"
    kubectl rollout restart deployment -n "$ns"
done