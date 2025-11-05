#!/bin/bash

# Define the YAML file
environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
emissary_file="${environment_manifest_root}/sync/extended/linkerd.yaml"

# Function to extract line numbers for first and last relevant lines
get_line_numbers() {
  first_line=$(awk '$0 == "# Emissary-ingress" {print NR+1}' "$emissary_file")
  last_line=$(awk -v start="$first_line" 'NR >= start && $0 == "---" {print NR-1; exit}' "$emissary_file")

  if [[ -z "$first_line" || -z "$last_line" ]]; then
    echo "Error: Unable to find the required lines."
    exit 1
  fi
}

# Function to update YAML with emissary values
update_yaml_with_emissary_values() {
  # Load expected values from the expected_values.json file
  if [[ "$CCU_SETUP" == "<1k" ]]; then
    expected_json_file="base/expected_values_1k.json"
  else
    expected_json_file="base/expected_values.json"
  fi

  emissary_vars=$(jq -r '.emissary | to_entries | map("\(.key)=\(.value)") | .[]' "$expected_json_file")
  emissary_version=$(jq -r '.emissary.EMISSARY_VERSION' "$expected_json_file")

  for var in $emissary_vars; do
    key=$(echo $var | cut -d '=' -f 1)
    value=$(echo $var | cut -d '=' -f 2)

    if [ "$key" == "EMISSARY_VERSION" ]; then
      continue
    fi

    if yq eval ".spec.postBuild.substitute.$key" "fileTemp.yaml" &>/dev/null; then
      echo "Updating $key with value $value"
      yq eval ".spec.postBuild.substitute.$key = \"$value\"" -i "fileTemp.yaml"
    else
      echo "Adding $key with value $value"
      yq eval ".spec.postBuild.substitute.$key = \"$value\"" -i "fileTemp.yaml"
    fi
  done

  if [[ -n "$emissary_version" ]]; then
    yq eval "(.spec.path |= sub(\"/emissary-ingress/[^/]+\", \"/emissary-ingress/$emissary_version\"))" -i "fileTemp.yaml"
    echo "Updated emissary-ingress paths to version $emissary_version"
  fi
}

# Function to inject content into the target YAML file
inject_content_into_target() {
  sed -i "${first_line},${last_line}d" "$emissary_file"

  awk -v line=$first_line '
    NR==line { 
      while ((getline line < "fileTemp.yaml") > 0) 
        print line 
    } 
    { print }
  ' "$emissary_file" > temp.yaml && mv temp.yaml "$emissary_file"

  echo "Injection complete."
  rm fileTemp.yaml
}

get_line_numbers
sed -n "$first_line,$last_line p" "$emissary_file" > fileTemp.yaml
update_yaml_with_emissary_values
inject_content_into_target
