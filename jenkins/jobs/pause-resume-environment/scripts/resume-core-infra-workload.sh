#!/bin/bash
# Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
source .env
echo "Resuming source gitrepository object..."
flux resume source git --all || true
echo "Resuming source helm chart object..."
flux resume source chart --all || true
echo "Resuming source helmrelease object..."
flux resume source helm --all || true
echo "Resuming source bucket object..."
flux resume source bucket --all || true

echo "Resuming linkerd helmrelease object..."
flux resume helmrelease --all -n linkerd || true
flux resume helmrelease --all -n linkerd-jaeger || true
flux resume helmrelease --all -n linkerd-smi || true

echo "Resuming imagerepository object..."
flux resume image repository --all || true
echo "Resuming imageupdateautomation object..."
flux resume image update --all || true

echo "Resuming kustomization object..."
flux resume kustomization --all || true

# echo "Resuming role-seeding cronjob..."
# flux resume kustomization roles-seeding-job-kustomization -n justice || true
# kubectl patch cronjobs roles-seeding -n justice -p '{"spec" : {"suspend" : false }}' || true

echo "scale up linkerd and emissary system..."
kubectl scale deployment --replicas=1 --all -n linkerd-jaeger || true
kubectl scale deployment --replicas=1 --all -n linkerd-smi || true
kubectl scale deployment --replicas=1 --all -n linkerd || true
echo "Waiting linkerd system exist..."
echo "Sleep for 300s..."
sleep 300
kubectl scale deployment --replicas=1 --all -n emissary || true
sleep 60