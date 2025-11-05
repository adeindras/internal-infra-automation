#!/bin/bash

# Set default values for parameters
aws_region=""
aws_account_id=""
env=""
component=""
output_file=""
min_eks_version="1.30"
eks_version="1.32"

# Function to log messages with timestamp
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1"
}

# Parse command-line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --region|-r)
            aws_region="$2"
            shift ;;
        --aws-account-id|-id)
            aws_account_id="$2"
            shift ;;
        --environment|-e)
            env="$2"
            shift ;;
        --component|-c)
            component="$2"
            shift ;;
        *)
            log "Unknown parameter passed: $1"
            exit 1 ;;
    esac
    shift
done

# Validate required parameters
if [[ -z "$aws_region" || -z "$aws_account_id" || -z "$env" || -z "$component" ]]; then
    log "ERROR: Missing required parameters. Usage: ./script.sh --region REGION --aws-account-id ACCOUNT_ID --env ENVIRONMENT --component COMPONENT (addon|controlplane|workernode)"
    exit 1
fi

# Set output file dynamically
timestamp=$(date +%Y-%m-%dT%H:%M:%SZ)
output_file="DbTablePostflightReport.txt"

log "Starting script execution..."

# compare_version "v1" "v2" "operator"
compare_version() {
    local v1="$1"
    local v2="$2"
    local operator="$3"

    local sorted_versions=$(echo -e "$v1\n$v2" | sort -V)
    case "$operator" in
        -eq) [[ "$v1" == "$v2" ]] ;;
        -ne) [[ "$v1" != "$v2" ]] ;;
        -gt) [[ "$v1" == "$(echo -e "$sorted_versions" | tail -n1)" && "$v1" != "$v2" ]] ;;
        -ge) [[ "$v1" == "$(echo -e "$sorted_versions" | tail -n1)" ]] ;;
        -lt) [[ "$v1" == "$(echo -e "$sorted_versions" | head -n1)" && "$v1" != "$v2" ]] ;;
        -le) [[ "$v1" == "$(echo -e "$sorted_versions" | head -n1)" ]] ;;
        *) echo "Invalid operator. Use -eq, -ne, -gt, -ge, -lt, or -le"; return 1 ;;
    esac
}

check_file(){
    local filename=$1

    ls $filename >/dev/null 2>&1 
    eks_yaml_output=$?

    if [[ $eks_yaml_output -ne 0 ]]; then
        log "ERROR: $filename not found in the current directory";
        exit 1
    else 
        log "$filename found in the current directory. continue.."
    fi
}

k8s_yaml_parser(){
    local k8s_version=$1
    local component=$2

    local major="${k8s_version%%.*}"
    local minor="${k8s_version##*.}" 

    output="$(yq ".KUBERNETES_VERSION.\"${major}\".\"${minor}\".${component}" eks.yaml)"

    if [[ -z $output || $output == "null" ]]; then 
        printf "⚠️ $component not found"
    else
        echo $output
    fi
}

# k -n kube-system get sa ebs-csi-driver available
check_k8s_resource(){
    local namespace=$1
    local resource=$2
    local name=$3
    local expected_status=$4

    if kubectl -n "$namespace" get "$resource" "$name" >/dev/null 2>&1; then
        actual_status="available"
    else
        actual_status="not_available"
    fi

    if [[ "$actual_status" == "$expected_status" ]]; then
        printf "✅ $resource $name are $expected_status";
    else
        printf "⚠️ $expected_status $resource $name";
    fi
}

# Functions for each criterion
check_k8s_control_plane() {
    local min_version=$1

    # check control plane version
    curr_control_plane_version=$(aws eks describe-cluster --name "$env" --region "$aws_region" --query "cluster.version" --output text 2>/dev/null)
    
    if [[ -z $curr_control_plane_version ]]; then
        echo "This env doesn't have Kubernetes"
    else
        if compare_version "$curr_control_plane_version" "$min_version"  "-ge"; then
            echo "✅ $curr_control_plane_version is up-to-date (>= $min_version)"
        else
            echo "⚠️ $curr_control_plane_version is outdated (< $min_version)"
        fi
    fi
}

check_k8s_worker_node() {
    local min_version=$1

    # lowest worker node
    curr_worker_node_version=$(kubectl get nodes --no-headers 2>/dev/null | awk '{ print $5 }' | sort -V | uniq | head -n 1 | sed 's|^v||' | awk -F'.' '{ print $1"."$2 }')

    if [[ -z $curr_worker_node_version ]]; then
        echo "This env doesn't have worker node"
    else
        if compare_version "$curr_worker_node_version" "$min_version"  "-ge"; then
            echo "✅ $curr_worker_node_version is up-to-date (>= $min_version)"
        else
            echo "⚠️  $curr_worker_node_version is outdated (< $min_version)"
        fi
    fi
}

check_karpenter_version() {
    local min_version=$1
    current_version="$(kubectl -n karpenter get deploy karpenter -o yaml 2>/dev/null | yq -r '.spec.template.spec.containers[0].image' | awk -F'[:@]' '{print $2}' | sed 's/^v//')"

    if [[ -z $current_version ]]; then
        echo "This env not using Karpenter"
    else
        if compare_version "$current_version" "$min_version"  "-ge"; then
            echo "✅ $current_version is up-to-date (>= $min_version)"
        else
            echo "⚠️ $current_version is outdated (< $min_version)"
        fi
    fi
}

check_falco_version() {
    local min_version=$1
    current_version="$(kubectl -n flux-system get ks -o yaml 2>/dev/null | yq '.items[] | select(.spec.path | contains("falco")) | .spec.path | split("/") | .[-1]' | sed 's|^v||')"

    if [[ -z $current_version ]]; then
        echo "This env not using Falco"
    else
        if compare_version "$current_version" "$min_version"  "-ge"; then
            echo "✅ $current_version is up-to-date (>= $min_version)"
        else
            echo "⚠️ $current_version is outdated (< $min_version)"
        fi
    fi
}

check_self_hosted_redis_manifest_version() {
    local manifest_version=$1
    current_version="$(kubectl -n flux-system get ks -o yaml 2>/dev/null | yq '.items[] | select(.spec.path | contains("manifests/platform/redis")) | .spec.path | split("/") | .[-2]')"

    if [[ -z $current_version ]]; then 
        echo "This env not using self-hosted redis"
    else
        if [[ $current_version == $manifest_version ]]; then
            echo "⚠️ $current_version need to patch (== v17.9.0-5)"
        else
            echo "✅ $current_version (== v16 or == v17.9.0-5)"
        fi 
    fi
}

check_error_sts_services() {
    kubectl get sts -A  --no-headers | awk '{ split($3, a, "/"); if (a[1] != a[2]) print $0 }' | grep -Ev 'Running|Completed' | awk {'print $1" "$2" "$3'}
}

check_suspended_ks() {
    kubectl get ks -A -o yaml | yq -r '.items[] | select(.spec.suspend == true) | .metadata.namespace + " " + .metadata.name'
}

find_critical_on_init_asg() {
    asg_name=$(aws --region $aws_region autoscaling describe-auto-scaling-groups \
    --query 'AutoScalingGroups[?length(Instances) >= `2` && contains(AutoScalingGroupName, `'"$env"'`)].AutoScalingGroupName' \
    --output text)
    
    if [[ -z $asg_name ]]; then
        echo "This env doesn't have asg critical-on-init"
    else
        echo $asg_name
    fi   
}

describe_asg() {
    local asg_name=$1

    instance_count=$(aws autoscaling describe-auto-scaling-groups --region "$aws_region" \
    --query "AutoScalingGroups[*]" --output yaml --no-cli-pager | \
    yq -r ".[] | select(.AutoScalingGroupName == \"$asg_name\") | .DesiredCapacity")

    if [[ "$instance_count" -gt 2 ]]; then
        echo "⚠️ ASG capacity $instance_count (== 2). Pls scaledown to 2!"
    elif [[ "$instance_count" -lt 2 ]]; then
        echo "⚠️ ASG capacity $instance_count (== 2). Pls scaleup to 2!"
    else
        echo "✅ ASG capacity $instance_count (== 2)"
    fi
}

describe_ami_asg(){
    local asg_name=$1
    local min_eks_version=$2

    launch_template_id=$(aws --region "$aws_region" autoscaling describe-auto-scaling-groups \
    --auto-scaling-group-name "$asg_name" \
    --output yaml | yq '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateId')

    ami_asg=$(aws --region "$aws_region" ec2 describe-launch-template-versions \
    --launch-template-id $launch_template_id \
    --query "LaunchTemplateVersions[0].LaunchTemplateData.ImageId" \
    --output text)

    ami_version=$(aws --region $aws_region ec2 describe-images --image-ids $ami_asg --output yaml | yq '.Images[0].Name' | cut -d'-' -f5)

    if [[ -z $ami_version ]]; then
        echo "This env not doesn't have asg critical-on-init"
    else
        if compare_version "$ami_version" "$min_eks_version"  "-ge"; then
            echo "✅ $ami_version is up-to-date (>= $min_eks_version)"
        else
            echo "⚠️ $ami_version is outdated (< $min_eks_version)"
        fi
    fi
}

describe_ami_awsnodetemplate(){
    local min_eks_version=$1
    karpenter_ami=$(kubectl get awsnodetemplate -o yaml | yq '.items[].status.amis[].name' | sort -V | uniq)
    karpenter_ami_version=$(kubectl get awsnodetemplate -o yaml | yq '.items[].status.amis[].name' | sed -E 's/.*-([0-9]+\.[0-9]+)-.*/\1/' | sort -V | head -n 1)

    if [[ -z $karpenter_ami_version ]]; then
        echo "This env not doesn't use asg critical-on-init"
    else
        if compare_version "$karpenter_ami_version" "$min_eks_version"  "-ge"; then
            echo "✅ $karpenter_ami_version is up-to-date (>= $min_eks_version)"
        else
            echo "⚠️ $karpenter_ami_version is outdated (< $min_eks_version)"
        fi
    fi
}

check_armada_services() {
    kubectl -n justice-play get sts consul-server &>/dev/null
    consul_server=$?

    kubectl -n justice-play get sts nomad-server &>/dev/null
    nomad_server=$?

    if [[ $consul_server -ne 0 && $nomad_server -ne 0 ]]; then
        echo "❌ Armada services are removed ";
    else 
        echo "✅ Armada services are available";
    fi
}

check_extend_services() {
    crossplane_deployment=$(kubectl -n crossplane-system get deploy crossplane &>/dev/null && echo true || echo false)

    extend_namespace=$(kubectl get namespace -o yaml 2>/dev/null | yq '[.items[] | select(.metadata.name | contains("ext-"))] | length > 0')

    if [[ "$crossplane_deployment" == true && "$extend_namespace" == true ]]; then
        echo "✅ Extend services are available"
    else
        echo "❌ Extend services are not available"
    fi
}

check_k8s_addon() {
    local name=$1
    local min_version=$2

    curr_addon_version=$(aws eks describe-addon --cluster-name "$env" --region "$aws_region" --addon-name "$name" --query "addon.addonVersion" --output text 2>/dev/null)

    if [[ -z $curr_addon_version ]]; then
        echo "This env doesn't have addon $name"
    else
        if compare_version "$curr_addon_version" "$min_version"  "-ge"; then
            echo "✅ $curr_addon_version is up-to-date (>= $min_version)"
        else
            echo "⚠️ $curr_addon_version is outdated (< $min_version)"
        fi
    fi
}

check_cluster_insight() {
    # kube-proxy version skew
    # Kubelet version skew
    # EKS add-on version compatibility
    # Cluster health issues
    local insight_component=$1

    insightStatus=$(aws --region "$aws_region" eks list-insights --cluster-name "$env" --output yaml --query "insights[?contains(name, \`$insight_component\`)].insightStatus" 2>/dev/null | yq '.[]')

    if [[ -z $insightStatus ]]; then
        echo "✅ $insight_component not found"
    elif [[ $(echo "$insightStatus" | yq -r .status) != "PASSING" ]]; then
        echo "⚠️ $insight_component reason: $(echo "$insightStatus" | yq -r .reason)"
    else
        echo "✅ $(echo "$insightStatus" | yq -r .reason)"
    fi
}

# Generate Report Table
print_table() {
    {
        echo "" > $output_file
        printf ""
        {
            echo "Post-flight Report for environment: $env"
            printf "+------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf "| %-15s | %-35s | %-80s |\n" "Component" "Criteria" "Result"
            printf "+------------------------------------------------------------------------------------------------------------------------------------------+\n"

            if [[ "$component" == "addon" ]]; then
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Min Control Plane Version         " "$(check_k8s_control_plane "$min_eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Min Worker Node Version           " "$(check_k8s_worker_node "$min_eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "EBS IRSA Deployed                 " "$(check_k8s_resource "kube-system" "sa" "ebs-csi-controller-sa" "available")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "No Self-Managed VPC-CNI           " "$(check_k8s_resource "flux-system" "ks" "vpc-cni" "not_available")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "No Self-Managed EBS CSI Driver    " "$(check_k8s_resource "flux-system" "ks" "ebs-csi-driver" "not_available")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Storage Class KS Exists           " "$(check_k8s_resource "flux-system" "ks" "storageclass" "available")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Addons vpc-cni                    " "$(check_k8s_addon "vpc-cni" "$(k8s_yaml_parser "$eks_version" "ADDONS.VPC_CNI_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Addons kube-proxy                 " "$(check_k8s_addon "kube-proxy" "$(k8s_yaml_parser "$eks_version" "ADDONS.KUBE_PROXY_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Addons aws-ebs-csi-driver         " "$(check_k8s_addon "aws-ebs-csi-driver" "$(k8s_yaml_parser "$eks_version" "ADDONS.EBS_CSI_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Addons" "Addons coredns                    " "$(check_k8s_addon "coredns" "$(k8s_yaml_parser "$eks_version" "ADDONS.COREDNS_VERSION")")"
            fi

            if [[ "$component" == "controlplane" ]]; then
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Min Control Plane Version  " "$(check_k8s_control_plane "$eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Cluster Health Issue       " "$(check_cluster_insight "Cluster")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "K8s Deprecated API         " "$(check_cluster_insight "Deprecated")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Min Worker Node Version    " "$(check_k8s_worker_node "$min_eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Karpenter Version          " "$(check_karpenter_version "$(k8s_yaml_parser "$eks_version" "KARPENTER_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Addons vpc-cni             " "$(check_k8s_addon "vpc-cni" "$(k8s_yaml_parser "$eks_version" "ADDONS.VPC_CNI_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Addons kube-proxy          " "$(check_k8s_addon "kube-proxy" "$(k8s_yaml_parser "$eks_version" "ADDONS.KUBE_PROXY_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Addons aws-ebs-csi-driver  " "$(check_k8s_addon "aws-ebs-csi-driver" "$(k8s_yaml_parser "$eks_version" "ADDONS.EBS_CSI_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Control Plane" "Addons coredns             " "$(check_k8s_addon "coredns" "$(k8s_yaml_parser "$eks_version" "ADDONS.COREDNS_VERSION")")"
            fi

            if [[ "$component" == "workernode" ]]; then
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Min Control Plane Version    " "$(check_k8s_control_plane "$eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Cluster Health Issue         " "$(check_cluster_insight "Cluster")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "K8s Deprecated API           " "$(check_cluster_insight "Deprecated")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Karpenter Version            " "$(check_karpenter_version "$(k8s_yaml_parser "$eks_version" "KARPENTER_VERSION")")"
                if [[ $(check_karpenter_version) != "This env not using Karpenter" ]]; then
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "ASG Critical-on-init Name    " "$(find_critical_on_init_asg)"
                asg_name=$(find_critical_on_init_asg);
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "ASG Critical-on-init Capacity" "$(describe_asg $asg_name)"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "AMI ASG Critical-on-init     " "$(describe_ami_asg $asg_name "$eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "AMI Karpenter                " "$(describe_ami_awsnodetemplate "$eks_version")"
                fi
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Min Worker Node Version      " "$(check_k8s_worker_node "$eks_version")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Addons vpc-cni               " "$(check_k8s_addon "vpc-cni" "$(k8s_yaml_parser "$eks_version" "ADDONS.VPC_CNI_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Addons kube-proxy            " "$(check_k8s_addon "kube-proxy" "$(k8s_yaml_parser "$eks_version" "ADDONS.KUBE_PROXY_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Addons aws-ebs-csi-driver    " "$(check_k8s_addon "aws-ebs-csi-driver" "$(k8s_yaml_parser "$eks_version" "ADDONS.EBS_CSI_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Addons coredns               " "$(check_k8s_addon "coredns" "$(k8s_yaml_parser "$eks_version" "ADDONS.COREDNS_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Armada Services              " "$(check_armada_services)"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Extend Services              " "$(check_extend_services)"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Falco                        " "$(check_falco_version "$(k8s_yaml_parser "$eks_version" "FALCO_VERSION")")"
                printf "| %-15s | %-35s | %-85s \n" "Worker Node" "Self Hosted Redis Version    " "$(check_self_hosted_redis_manifest_version "v17.9.0-3")"
                error_list_sts=$(check_error_sts_services)
                if [[ -z "$error_list_sts" ]]; then
                    printf "| %-15s | %-35s | ✅ %-77s \n" "Worker Node" "Error Statefulset" "No error statefulset"
                else
                    printf "| %-15s | %-35s | ⚠️  %-77s \n" "Worker Node" "Error Statefulset" "stsNamespace stsName stsNotReady"
                    while IFS= read -r line; do
                        printf "| %-15s | %-35s | %-80s \n" "" "" "$line"
                    done <<< "$error_list_sts"
                fi
                suspended_list_ks=$(check_suspended_ks)
                if [[ -z "$suspended_list_ks" ]]; then
                    printf "| %-15s | %-35s | ✅ %-77s \n" "Worker Node" "Suspended Kustomization" "Not found suspended kustomization"
                else
                    printf "| %-15s | %-35s | ⚠️  %-77s \n" "Worker Node" "Suspended Kustomization" "ksNamespace ksName"
                    while IFS= read -r line; do
                        printf "| %-15s | %-35s | %-80s \n" "" "" "$line"
                    done <<< "$suspended_list_ks"
                fi
            fi
            printf "+------------------------------------------------------------------------------------------------------------------------------------------+\n"
            printf ""
        } >> $output_file
        echo "" >> $output_file

        cat $output_file
    }
}

check_file "eks.yaml"
print_table