#!/bin/bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

set -euo pipefail

PASS=()
FAIL=()

# --- Cleanup first to reset
kubectl delete ns ${TEST_NS} --ignore-not-found=true
kubectl delete ns ${DB_NS} --ignore-not-found=true

echo "🛠️ Creating test namespaces"
kubectl create ns ${TEST_NS}
kubectl annotate ns ${TEST_NS} linkerd.io/inject=enabled --overwrite

kubectl create ns ${DB_NS}  # do not annotate for Linkerd

# --- Deploy public web app (internet-facing)
echo "🌍 Deploying public hello web pod with service"
cat <<EOF | kubectl apply -n ${TEST_NS} -f -
apiVersion: v1
kind: Service
metadata:
  name: hello-svc
spec:
  selector:
    app: hello
  ports:
  - port: 80
    targetPort: 80
---
apiVersion: apps/v1
kind: Deployment
metadata:
    name: hello
spec:
    replicas: 1
    selector:
      matchLabels:
        app: hello
    template:
      metadata:
        labels:
          app: hello
      spec:
        containers:
        - name: hello
          image: ${APP_IMAGE}
          ports:
          - containerPort: 80
EOF

echo "⏳ Waiting for hello deployment to be ready..."
kubectl -n ${TEST_NS} rollout status deploy/hello --timeout=60s

host=$(kubectl get ingress emissary-ingress -n emissary -o json | jq -r '[.spec.rules[] | select(.http.paths[].backend.service.name == "emissary-ingress") | .host][0]')
curl -sSfL "https://$host/iam/version" >/dev/null && PASS+=("Service accessible from Internet") || FAIL+=("Service accessible from Internet")

echo "🛢️ Deploying PostgreSQL pod in non-meshed namespace"
kubectl apply -n ${DB_NS} -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: postgres
  labels:
    app: postgres
spec:
  containers:
    - name: postgres
      image: postgres:16
      env:
        - name: POSTGRES_PASSWORD
          value: rootpass
      ports:
        - containerPort: 5432
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
spec:
  selector:
    app: postgres
  ports:
  - port: 5432
    targetPort: 5432
EOF

echo "⏳ Waiting for PostgreSQL pod..."
kubectl wait --for=condition=Ready pod/postgres -n ${DB_NS} --timeout=90s || true

echo "🚀 Deploying test pod with psql client"
cat <<EOF | kubectl apply -n ${TEST_NS} -f -
apiVersion: v1
kind: Pod
metadata:
    name: tester-db
spec:
    containers:
    - name: db
      image: postgres:16
      command: ["sleep", "3600"]
EOF
echo "⏳ Waiting for test db pod to be ready..."
kubectl wait --for=condition=Ready pod/tester-db -n ${TEST_NS} --timeout=60s

# --- Deploy test pod to meshed ns
echo "🚀 Deploying test pod with curl"
cat <<EOF | kubectl apply -n ${TEST_NS} -f -
apiVersion: v1
kind: Pod
metadata:
    name: tester
spec:
    containers:
    - name: curl
      image: ${TEST_IMAGE}
      command: ["sleep", "3600"]
EOF

echo "⏳ Waiting for test pod to be ready..."
kubectl wait --for=condition=Ready pod/tester -n ${TEST_NS} --timeout=60s

echo "🔁 Testing Pod-to-DB connection..."
kubectl exec -n ${TEST_NS} tester-db -c db -- \
  bash -c "PGPASSWORD=rootpass psql -h postgres.${DB_NS}.svc.cluster.local -U postgres -c '\l'" && \
  PASS+=("PostgreSQL connection working") || FAIL+=("PostgreSQL connection working")

# --- Pod-to-pod communication test (curl to hello)
echo "🔁 Testing pod-to-pod curl..."
kubectl exec -n ${TEST_NS} tester -c curl -- curl -sSf http://hello-svc.${TEST_NS}.svc.cluster.local >/dev/null && \
    PASS+=("Pod-to-pod curl communication") || FAIL+=("Pod-to-pod curl communication")


# --- Summary
echo ""
echo "======================"
echo "✅ Validation Summary:"
echo "======================"
for item in "${PASS[@]}"; do echo "✔️  $item"; done
echo ""
if [ ${#FAIL[@]} -eq 0 ]; then
    echo "🎉 All checks passed!"
else
    echo "======================"
    echo "❌ Failed Checks:"
    echo "======================"
    for item in "${FAIL[@]}"; do echo "❌ $item"; done
    exit 1
fi
