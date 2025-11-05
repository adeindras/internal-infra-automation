#!/bin/bash

# Find and replace occurrences of specific names with "ags-infrastructure"
find -type f -exec sed -i 's/- name: linkerd/- name: ags-infrastructure/g' {} +
find -type f -exec sed -i 's/- name: emissary-ingress/- name: ags-infrastructure/g' {} +
find -type f -exec sed -i 's/- name: karpenter-templates/- name: ags-karpenter-templates/g' {} +
find -type f -exec sed -i 's/- name: karpenter/- name: ags-infrastructure/g' {} +
find -type f -exec sed -i 's/- name: opentelemetry-collector/- name: ags-observability/g' {} +
find -type f -exec sed -i 's/- name: logging-fluentd/- name: ags-observability/g' {} +

# Find and remove duplicate lines of "- name: ags-infrastructure"
for file in $(find . -type f -name '*.yaml'); do
  echo "Processing $file..."
  yq -i '.spec.dependsOn = [.spec.dependsOn | unique_by(.name) | .[]]' "$file" || true
done
