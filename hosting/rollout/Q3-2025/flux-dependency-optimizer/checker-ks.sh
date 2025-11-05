#!/usr/bin/env bash
# kustomization-dep-audit.sh
# Requires yq v4.3x

LIST_FILE="$WORKSPACE/hosting/rollout/Q3-2025/flux-dependency-optimizer/list-ks-update-dependson.yaml"

REPORT_FILE="$WORKSPACE/hosting/rollout/Q3-2025/flux-dependency-optimizer/report.txt"

LIST_KS_CHANGES_FILE="$WORKSPACE/hosting/rollout/Q3-2025/flux-dependency-optimizer/list-ks-change.tmp"
echo -e "kustomization:" > $LIST_KS_CHANGES_FILE

# Table column widths
COL1=35  # kustomization_name (will truncate >35 chars)
COL2=30  # existing_dependsOn
COL3=30  # changes_dependsOn
COL4=16  # changes_required

# Print table header
printf "%-${COL1}s %-${COL2}s %-${COL3}s %-${COL4}s\n" \
  "kustomization_name" "existing_dependsOn" "changes_dependsOn" "changes_required" > "$REPORT_FILE"
printf "%-${COL1}s %-${COL2}s %-${COL3}s %-${COL4}s\n" \
  "--------------------" "------------------------------" "------------------------------" "----------------" >> "$REPORT_FILE"

# Loop through files containing "name: flux"
echo "Scanning for Kustomizations..."
grep -rl -e "name: flux" -e "/cluster-variables" . 2>/dev/null | while read -r file; do
  # Extract Kustomization name
  yq e -N 'select(.kind == "Kustomization" and .metadata.name != "") | select(.metadata | .name != "deployments" and .name != "") | .metadata.name' "$file" 2>/dev/null | while read -r ksname; do

    # Skip if no Kustomization
    if [ -z "$ksname" ]; then
        continue
    fi

    # Extract existing dependsOn
    existing_depends=$(yq e -N 'select(.kind == "Kustomization") | select(.metadata.name == "'"$ksname"'") | .spec.dependsOn[].name' "$file" 2>/dev/null | paste -sd "," -)

    # Skip printing if no dependsOn
    if [ -z "$existing_depends" ]; then
        continue
    fi

    # Extract changes (exclude flux, flux-system, & flux-volume)
    exists=$(yq e ".kustomization | has(\"$ksname\")" "$LIST_FILE")
    if [ "$exists" == "true" ]; then
        changes_depends=$(yq e ".kustomization.\"$ksname\".dependsOn[].name" "$LIST_FILE" | paste -sd "," -)
    else
        changes_depends=$(yq e -N 'select(.kind == "Kustomization") | select(.metadata.name == "'"$ksname"'") | .spec.dependsOn[] | select(.name != "flux" and .name != "flux-system" and .name != "flux-volume") | .name' "$file" 2>/dev/null | paste -sd "," -)
    fi
    
    # changes_required if different
    changes_required=$(if [ "$existing_depends" != "$changes_depends" ]; then echo "yes"; else echo "no"; fi)

    if [ "$changes_required" == "yes" ]; then
      if [ -z "$changes_depends" ]; then
        echo -e "  ${ksname}:\n    dependsOn: []" >> $LIST_KS_CHANGES_FILE
      else
        echo -e "  ${ksname}:\n    dependsOn:\n      - name: $changes_depends" >> $LIST_KS_CHANGES_FILE
      fi
      sed -i 's/,/\n      - name: /g' $LIST_KS_CHANGES_FILE
    fi

    # Truncate kustomization_name to 20 chars
    if [ ${#ksname} -gt $COL1 ]; then
        ks_display="${ksname:0:$((COL1-3))}..."
    else
        ks_display="$ksname"
    fi

    # Split at commas for multiline display
    IFS=',' read -ra existing_arr <<< "$existing_depends"
    IFS=',' read -ra changes_arr <<< "$changes_depends"

    # Get max number of lines between both arrays
    max_lines=${#existing_arr[@]}
    if [ ${#changes_arr[@]} -gt $max_lines ]; then
        max_lines=${#changes_arr[@]}
    fi

    # Print each line
    for i in $(seq 0 $((max_lines-1))); do
        # Only print name on first line
        if [ "$i" -eq 0 ]; then
        name_col=$(printf "%-${COL1}s" "$ks_display")
        changes_col=$(printf "%-${COL4}s" "$changes_required")
        
        else
        name_col=$(printf "%-${COL1}s" "")
        changes_col=$(printf "%-${COL4}s" "")
        
        fi

        # Existing dependsOn
        if [ -n "${existing_arr[$i]}" ]; then
        exist_col=$(printf "%-${COL2}s" "${existing_arr[$i]}")
        
        else
        exist_col=$(printf "%-${COL2}s" "")
        
        fi

        # Updated dependsOn
        if [ -n "${changes_arr[$i]}" ]; then
        change_col=$(printf "%-${COL3}s" "${changes_arr[$i]}")
        
        else
        change_col=$(printf "%-${COL3}s" "")
        
        fi

        # Print row
        printf "%s %s %s %s\n" "$name_col" "$exist_col" "$change_col" "$changes_col" >> "$REPORT_FILE"
    done
  done
done
cat "$REPORT_FILE"