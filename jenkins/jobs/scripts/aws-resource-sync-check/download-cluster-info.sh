#!/bin/bash

# export AWS_ACCESS_KEY_ID=""
# export AWS_SECRET_ACCESS_KEY=""
# export AWS_SESSION_TOKEN=""

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <accountName> e.g. accelbyte-justice-stage"
    exit 1
fi

accountName=$1
WORKDIR="$PWD"
OUTPUT_DIR="$WORKDIR/output"
OUTPUT_DIR_CLUSTER_INFO="${OUTPUT_DIR}/clusterinfo"
OUTPUT_IAC_DIR="${OUTPUT_DIR}/iacinfo"
OUTPUT_FILE_DIRS="${OUTPUT_DIR}/directory_lists"
IAC_REPO_DIR="${OUTPUT_DIR}/iac"

echo "deleting folders..."
rm -rf "$OUTPUT_DIR_CLUSTER_INFO"
rm -rf "$OUTPUT_IAC_DIR"
rm -rf "$OUTPUT_FILE_DIRS"
echo "deleting folders complete..."

mkdir -p "$OUTPUT_DIR_CLUSTER_INFO"
mkdir -p "$OUTPUT_IAC_DIR"

IFS='-' read -r customer project environment <<< "$accountName"

echo "Parsed values:"
echo "  Customer name: $customer"
echo "  Project name: $project"
echo "  Environment grade: $environment"
echo "  Output Directory: $OUTPUT_DIR_CLUSTER_INFO"

# export BITBUCKET_ACCESS_TOKEN=""
# response=$(bitbucket-downloader -f $customer/$project/$environment/cluster-information.env -r master -s deployments -o $OUTPUT_DIR_CLUSTER_INFO)
# http_code=$(echo "${response}" | grep 'HTTP/*' | cut -d' ' -f2-)
# if [[ "$http_code" == "404 Not Found" ]]; then
#   echo "File not found, exiting... "
#   exit 1
# fi

# cluster_info="$OUTPUT_DIR_CLUSTER_INFO/$customer/$project/$environment/cluster-information.env"
# cs_info=$(yq e $cluster_info)

# IFS=' ' read -ra vars <<< "$cs_info"

# for var in "${vars[@]}"; do
#   IFS='=' read -r key value <<< "$var"
#   export "$key"="$value"
# done

echo "  AWS Account ID: $AWS_ACCOUNT_ID"
echo "  EKS Cluster Name: $EKS_CLUSTER_NAME"
echo "  AWS Region: $AWS_REGION"

echo "current directory $PWD"


# git clone --depth 1 --single-branch -b master git@bitbucket.org:accelbyte/iac.git
target_dir="$IAC_REPO_DIR/live/$AWS_ACCOUNT_ID/$customer/$project/$AWS_REGION/$environment"
if [[ ! -d "$target_dir" ]]; then
    echo "Target directory '$target_dir' not found in the cloned repository."$'\n'
    exit 1
fi

true > "$OUTPUT_FILE_DIRS"

folders=("rds" "msk" "elasticache" "opensearch") # Explicit folder names
prefixes=("docdb")                               # Folder name prefixes to match

for folder in "${folders[@]}"; do
    echo $'\n'"Searching for folder: $folder in $target_dir"
    folder_results=$(find "$target_dir" -type d -name "$folder" \
        -not -iname '*deprecated*' ! -path "*/.terragrunt-cache/*" ! -path "*/*-deprecated/*" 2>/dev/null)
    
    if [[ -n "$folder_results" ]]; then
        echo -e "\nFound folder '$folder' at:"
        echo "$folder_results"
        
        while IFS= read -r dir; do
            echo -e "\nSearching recursively for 'terragrunt.hcl' under: $dir"
            terragrunt_files=$(find "$dir" -type f -name "terragrunt.hcl" \
              -not -iname '*deprecated*' ! -path "*/.terragrunt-cache/*" ! -path "*/*-deprecated/*" 2>/dev/null)
            
            if [[ -n "$terragrunt_files" ]]; then
                while IFS= read -r terragrunt_file; do
                    echo -e "\nFound 'terragrunt.hcl': $terragrunt_file"
                    echo "$terragrunt_file" >> "$OUTPUT_FILE_DIRS"

                    terragrunt_dir=$(dirname "$terragrunt_file")
                    echo -e "\nRunning 'terragrunt plan' for $folder in directory: $terragrunt_dir"
                    filename=$(echo "$terragrunt_dir" | awk -F'/' '{print $(NF-1)"/"$NF}' | sed 's|/|_|g')
                    (cd "$terragrunt_dir" && terragrunt init && terragrunt plan -out="$OUTPUT_IAC_DIR/$filename.tfplan" && terragrunt show -json "$OUTPUT_IAC_DIR/$filename.tfplan" > "$OUTPUT_IAC_DIR/$filename.json")
                    # jq -j '.?' "$OUTPUT_IAC_DIR/$filename.json"
                    jq -j '[.? | .resource_drift[] | {address: .address, changes: .change.before}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}drift_report_before.json"
                    jq -j '[.? | .resource_drift[] | {address: .address, changes: .change.after}]' \
                            "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}drift_report_after.json"
                    diff "$OUTPUT_IAC_DIR/${filename}drift_report_before.json" "$OUTPUT_IAC_DIR/${filename}drift_report_after.json" > "$OUTPUT_IAC_DIR/${filename}_drift_report"

                    jq -j '[.? | .resource_changes[] | {address: .address, changes: .change.before}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}changes_report_before.json"
                    jq -j '[.? | .resource_changes[] | {address: .address, changes: .change.after}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}changes_report_after.json"
                    diff "$OUTPUT_IAC_DIR/${filename}changes_report_before.json" "$OUTPUT_IAC_DIR/${filename}changes_report_after.json" > "$OUTPUT_IAC_DIR/${filename}_changes_report"
                done <<< "$terragrunt_files"
            else
                echo "No 'terragrunt.hcl' found under: $dir"
            fi
        done <<< "$folder_results"
    else
        echo "Folder '$folder' not found in '$target_dir'."$'\n'
    fi
done

echo "current directory $PWD"
for prefix in "${prefixes[@]}"; do
    echo $'\n'"Searching for folders with prefix: $prefix in $target_dir"
    folder_results=$(find "$target_dir" -type d -name "${prefix}*" \
        -not -iname '*deprecated*' ! -path "*/.terragrunt-cache/*" ! -path "*/*-deprecated/*" 2>/dev/null)
    
    if [[ -n "$folder_results" ]]; then
        echo -e "\nFound folders with prefix '$prefix' at:"
        echo "$folder_results"
        
        while IFS= read -r dir; do
            echo -e "\nSearching recursively for 'terragrunt.hcl' under: $dir"
            terragrunt_files=$(find "$dir" -type f -name "terragrunt.hcl" \
                -not -name '*deprecated*' ! -path "*/.terragrunt-cache/*" ! -path "*/*-deprecated/*" 2>/dev/null)
            
            if [[ -n "$terragrunt_files" ]]; then
                while IFS= read -r terragrunt_file; do
                    echo -e "\nFound 'terragrunt.hcl': $terragrunt_file"
                    echo "$terragrunt_file" >> "$OUTPUT_FILE_DIRS"

                    terragrunt_dir=$(dirname "$terragrunt_file")
                    echo -e "\nRunning 'terragrunt plan' for $prefix in directory: $terragrunt_dir"
                    filename=$(echo "$terragrunt_dir" | awk -F'/' '{print $(NF-1)"/"$NF}' | sed 's|/|_|g')
                    (cd "$terragrunt_dir" && terragrunt init && terragrunt plan -out="$OUTPUT_IAC_DIR/$filename.tfplan" && terragrunt show -json "$OUTPUT_IAC_DIR/$filename.tfplan" > "$OUTPUT_IAC_DIR/$filename.json")
                    # jq -j '.?' "$OUTPUT_IAC_DIR/$filename.json"
                    jq -j '[.? | .resource_drift[] | {address: .address, changes: .change.before}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}drift_report_before.json"
                    jq -j '[.? | .resource_drift[] | {address: .address, changes: .change.after}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}drift_report_after.json"

                    diff "$OUTPUT_IAC_DIR/${filename}drift_report_before.json" "$OUTPUT_IAC_DIR/${filename}drift_report_after.json" > "$OUTPUT_IAC_DIR/${filename}_drift_report"
                    jq -j '[.? | .resource_changes[] | {address: .address, changes: .change.before}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}changes_report_before.json"
                    jq -j '[.? | .resource_changes[] | {address: .address, changes: .change.after}]' \
                        "$OUTPUT_IAC_DIR/$filename.json" > "$OUTPUT_IAC_DIR/${filename}changes_report_after.json"
                    diff "$OUTPUT_IAC_DIR/${filename}changes_report_before.json" "$OUTPUT_IAC_DIR/${filename}changes_report_after.json" > "$OUTPUT_IAC_DIR/${filename}_changes_report"
                done <<< "$terragrunt_files"
            else
                echo "No 'terragrunt.hcl' found under: $dir"
            fi
        done <<< "$folder_results"
    else
        echo "No folders with prefix '$prefix' found in '$target_dir'."$'\n'
    fi
done

shopt -s nullglob
for file in output/iacinfo/*_{drift,changes}_report; do
    echo "Contents of $file:"
    if [[ ! -s "$file" ]]; then
        echo "infra check failed"
    else
        cat "$file"
    fi
    echo -e "\n----------------------\n"
done
shopt -u nullglob

echo "deleting iac folders after run..."
rm -rf "$IAC_REPO_DIR"
echo "deleting iac folders after run..."
