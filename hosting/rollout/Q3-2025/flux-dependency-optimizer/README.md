# 🧩 Kustomization Dependency Optimizer

This tool provides a structured way to **optimize `dependsOn` usage** in Flux Kustomizations. It helps reduce unnecessary reconciliation delays and ensures more reliable ordering between components.

## 📌 Overview

Flux's `Kustomization` resources support a `dependsOn` field to control reconciliation order. However, as the number of components grows, **manually managing dependencies** becomes inefficient and it can possibly unrelated again once the resource has created successfully. 
This optimizer improves dependency management by:

- Reordering kustomizations based on **value dependency**
- Allowing **parallel reconciliations** where dependencies are independent

---

## 🚀 Features

- 🧠 **Value-based dependency** — Ensures proper sequencing without long linear chains.
- 🏗 **Automatically** — Update dependency kustomizations listed [here](hosting/rollout/Q3-2025/flux-dependency-optimizer/list-ks-update-dependson.yaml) automatically.

---
## Runbook to Execute this pipeline
https://accelbyte.atlassian.net/wiki/x/G4B59Q

---
## 🧠 Example: Before vs After

### ❌ Before
```yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1beta2
kind: Kustomization
metadata:
  name: emissary-ingress
  namespace: flux-system
spec:
  dependsOn:
    - name: linkerd
```
### ✅ After
```yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1beta2
kind: Kustomization
metadata:
  name: emissary-ingress
  namespace: flux-system
spec:
  dependsOn: []
```