#!/bin/bash
# filepath: ../sync/update-kustomization.sh

LIST_FILE="$WORKSPACE/hosting/rollout/Q3-2025/flux-dependency-optimizer/list-ks-update-dependson.yaml"

LIST_KS_CHANGES_FILE="$WORKSPACE/hosting/rollout/Q3-2025/flux-dependency-optimizer/list-ks-change.tmp"

# Loop through files containing "name: flux"
grep -rl -e "name: flux" -e "/cluster-variables" . 2>/dev/null | while read -r file; do
  # Only process YAML documents that have both kind and metadata.name
  yq -r 'select(.kind and .metadata.name) | .kind + " " + .metadata.name' "$file" | while read -r kind ksname; do

    # Skip if not a Kustomization
    if [ "$kind" != "Kustomization" ]; then
        continue
    fi
    
    kslist=$(yq e '.kustomization | keys | .[] | select(. == "'"$ksname"'")' "$LIST_KS_CHANGES_FILE")
    
    # Skip if kslist is null/empty
    if [[ -z "$kslist" || "$kslist" == "null" ]]; then
      continue
    fi

    changes_depends=$(yq e -o=json ".kustomization.$kslist.dependsOn" "$LIST_KS_CHANGES_FILE")

    echo "Updating kustomization dependency in $kslist"
    yq e -i '. as $item | (select($item.kind == "Kustomization" and $item.metadata.name == "'"$kslist"'") | .spec.dependsOn) |= '"$changes_depends"'' "$file"
  done
done