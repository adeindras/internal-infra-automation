#!/bin/bash

set -euo pipefail

environment_manifest_root="${WORKSPACE}/iac/manifests/clusters/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}"
environment_deployment_root="${WORKSPACE}/deployments/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}"

# Available environment variables
echo "Customer name: ${CUSTOMER_NAME}"
echo "Project: ${PROJECT_NAME}"
echo "Environment: ${ENVIRONMENT_NAME}"
echo "AWS Account ID: ${AWS_ACCOUNT}"
echo "AWS Region: ${AWS_REGION}"

get_max_width() {
    local col=("$@")
    local max_width=0
    for item in "${col[@]}"; do
        (( ${#item} > max_width )) && max_width=${#item}
    done
    echo "$max_width"
}

# Define namespaces
cronjob_ns="flux-system"
ingress_ns="emissary"

# Check if CronJob suspended
actual_suspend=$(kubectl get cronjob -n "$cronjob_ns" ecr-credentials-sync -o=jsonpath='{.spec.suspend}' 2>/dev/null || echo "Not exist")

# Check if Websocket exists
if kubectl get ingress -n "$ingress_ns" justice-websocket &>/dev/null; then
    actual_ingress="Exist"
    ingress_version=$(kubectl get ks -n flux-system emissary-ingress-websocket -o=jsonpath='{.spec.path}' | awk -F'/' '{print $(NF-1)}')
else
    actual_ingress="Not exist"
    ingress_version="Not exist"
fi

# Check the namespaces annotation
actual_annotation=$(kubectl get namespace justice -o=jsonpath='{.metadata.annotations.config\.linkerd\.io/skip-outbound-ports}' 2>/dev/null)
actual_annotation=${actual_annotation:-"Not exist"}

# Load expected values from the expected_values.json file
if [[ "$CCU_SETUP" == "<1k" ]]; then
  expected_values="$(cat base/expected_values_1k.json)"
else
  expected_values="$(cat base/expected_values.json)"
fi

# Prepare separate lists for Linkerd and Others resources
emissary_rows=("Resource Type|Name|Actual|Expected")
others_rows=("Resource Type|Name|Actual|Expected")
websocket_rows=("Resource Type|Name|Actual|Expected")

get_expected_value() {
    echo "$expected_values" | jq -r "$1"
}

get_k8s_value() {
    kubectl get "$1" -n "$2" "$3" -o=jsonpath="$4" 2>/dev/null || echo "Not exist"
}

# Add Linkerd-related data
emissary_rows+=("Version|emissary-ingress|$(get_k8s_value ks flux-system emissary-ingress '{.spec.path}' | awk -F'/' '{print $(NF-1)}')|$(get_expected_value '.emissary.EMISSARY_VERSION')")
emissary_rows+=("HPA|EMISSARY_MIN_REPLICAS|$(get_k8s_value hpa $ingress_ns emissary-ingress '{.spec.minReplicas}')|$(get_expected_value '.emissary.EMISSARY_MIN_REPLICAS')")
emissary_rows+=("HPA|EMISSARY_MAX_REPLICAS|$(get_k8s_value hpa $ingress_ns emissary-ingress '{.spec.maxReplicas}')|$(get_expected_value '.emissary.EMISSARY_MAX_REPLICAS')")
emissary_rows+=("HPA|EMISSARY_HPA_CPU_THRESHOLD|$(get_k8s_value hpa $ingress_ns emissary-ingress '{.spec.metrics[?(@.resource.name=="cpu")].resource.target.averageUtilization}')|$(get_expected_value '.emissary.EMISSARY_HPA_CPU_THRESHOLD')")
emissary_rows+=("HPA|EMISSARY_HPA_MEM_THRESHOLD|$(get_k8s_value hpa $ingress_ns emissary-ingress '{.spec.metrics[?(@.resource.name=="memory")].resource.target.averageUtilization}')|$(get_expected_value '.emissary.EMISSARY_HPA_MEM_THRESHOLD')")
emissary_rows+=("HPA|EMISSARY_HPA_SCALE_DOWN_SELECT_POLICY|$(get_k8s_value hpa $ingress_ns emissary-ingress '{.spec.behavior.scaleDown.selectPolicy}')|$(get_expected_value '.emissary.EMISSARY_HPA_SCALE_DOWN_SELECT_POLICY')")
emissary_rows+=("Deployment|EMISSARY_CPU_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress '{.spec.template.spec.containers[0].resources.requests.cpu}')|$(get_expected_value '.emissary.EMISSARY_CPU_REQUEST')")
emissary_rows+=("Deployment|EMISSARY_MEMORY_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress '{.spec.template.spec.containers[0].resources.requests.memory}')|$(get_expected_value '.emissary.EMISSARY_MEMORY_REQUEST')")
emissary_rows+=("Deployment|EMISSARY_MEMORY_LIMT|$(get_k8s_value deployment $ingress_ns emissary-ingress '{.spec.template.spec.containers[0].resources.limits.memory}')|$(get_expected_value '.emissary.EMISSARY_MEMORY_LIMT')")
emissary_rows+=("Deployment|LINKERD_PROXY_CPU_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress '{.spec.template.metadata.annotations.config\.linkerd\.io/proxy-cpu-request}')|$(get_expected_value '.emissary.LINKERD_PROXY_CPU_REQUEST')")
emissary_rows+=("Deployment|LINKERD_PROXY_MEMORY_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress '{.spec.template.metadata.annotations.config\.linkerd\.io/proxy-memory-request}')|$(get_expected_value '.emissary.LINKERD_PROXY_MEMORY_REQUEST')")
emissary_rows+=("Deployment|LINKERD_PROXY_MEMORY_LIMIT|$(get_k8s_value deployment $ingress_ns emissary-ingress '{.spec.template.metadata.annotations.config\.linkerd\.io/proxy-memory-limit}')|$(get_expected_value '.emissary.LINKERD_PROXY_MEMORY_LIMIT')")


# Add WebSocket-related data
websocket_rows+=("Ingress|justice-websocket|$actual_ingress|Exist")
websocket_rows+=("Version|emissary-ingress-websocket|$ingress_version|$(get_expected_value '.websocket.WEBSOCKET_VERSION')")
websocket_rows+=("HPA|EMISSARY_WEBSOCKET_MIN_REPLICAS|$(get_k8s_value hpa $ingress_ns emissary-ingress-websocket '{.spec.minReplicas}')|$(get_expected_value '.websocket.EMISSARY_WEBSOCKET_MIN_REPLICAS')")
websocket_rows+=("HPA|EMISSARY_WEBSOCKET_MAX_REPLICAS|$(get_k8s_value hpa $ingress_ns emissary-ingress-websocket '{.spec.maxReplicas}')|$(get_expected_value '.websocket.EMISSARY_WEBSOCKET_MAX_REPLICAS')")
websocket_rows+=("HPA|EMISSARY_WEBSOCKET_HPA_CPU_THRESHOLD|$(get_k8s_value hpa $ingress_ns emissary-ingress-websocket '{.spec.metrics[0].resource.target.averageUtilization}')|$(get_expected_value '.websocket.EMISSARY_WEBSOCKET_HPA_CPU_THRESHOLD')")
websocket_rows+=("HPA|EMISSARY_WEBSOCKET_HPA_SCALE_DOWN_SELECT_POLICY|$(get_k8s_value hpa $ingress_ns emissary-ingress-websocket '{.spec.behavior.scaleDown.selectPolicy}')|$(get_expected_value '.websocket.EMISSARY_WEBSOCKET_HPA_SCALE_DOWN_SELECT_POLICY')")
websocket_rows+=("Deployment|EMISSARY_CPU_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress-websocket '{.spec.template.spec.containers[0].resources.requests.cpu}')|$(get_expected_value '.websocket.EMISSARY_CPU_REQUEST')")
websocket_rows+=("Deployment|EMISSARY_MEMORY_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress-websocket '{.spec.template.spec.containers[0].resources.requests.memory}')|$(get_expected_value '.websocket.EMISSARY_MEMORY_REQUEST')")
websocket_rows+=("Deployment|EMISSARY_MEMORY_LIMT|$(get_k8s_value deployment $ingress_ns emissary-ingress-websocket '{.spec.template.spec.containers[0].resources.limits.memory}')|$(get_expected_value '.websocket.EMISSARY_MEMORY_LIMT')")
websocket_rows+=("Deployment|LINKERD_PROXY_CPU_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress-websocket '{.spec.template.metadata.annotations.config\.linkerd\.io/proxy-cpu-request}')|$(get_expected_value '.websocket.LINKERD_PROXY_CPU_REQUEST')")
websocket_rows+=("Deployment|LINKERD_PROXY_MEMORY_REQUEST|$(get_k8s_value deployment $ingress_ns emissary-ingress-websocket '{.spec.template.metadata.annotations.config\.linkerd\.io/proxy-memory-request}')|$(get_expected_value '.websocket.LINKERD_PROXY_MEMORY_REQUEST')")
websocket_rows+=("Deployment|LINKERD_PROXY_MEMORY_LIMIT|$(get_k8s_value deployment $ingress_ns emissary-ingress-websocket '{.spec.template.metadata.annotations.config\.linkerd\.io/proxy-memory-limit}')|$(get_expected_value '.websocket.LINKERD_PROXY_MEMORY_LIMIT')")

# Add Others-related data
others_rows+=("CronJob|ecr-credentials-sync (Suspended)|$actual_suspend|$(echo "$expected_values" | jq -r '.others.ECR_CRONJOB_SUSPENDED')")
others_rows+=("Annotation|config.linkerd.io/skip-outbound-ports|$actual_annotation|$(echo "$expected_values" | jq -r '.others.JUSTICE_NS_ANNOTATION')")

# Function to print tables
print_table() {
    local title="$1"
    shift
    local rows=("$@")
    
    local col1=() col2=() col3=() col4=()
    for row in "${rows[@]}"; do
        IFS='|' read -r c1 c2 c3 c4 <<< "$row"
        col1+=("$c1") col2+=("$c2") col3+=("$c3") col4+=("$c4")
    done

    local width1=$(get_max_width "${col1[@]}")
    local width2=$(get_max_width "${col2[@]}")
    local width3=$(get_max_width "${col3[@]}")
    local width4=$(get_max_width "${col4[@]}")

    printf "\n%s\n" "$title"
    printf "%-${width1}s | %-${width2}s | %-${width3}s | %-${width4}s\n" "Resource Type" "Name" "Actual" "Expected"
    printf "%-${width1}s-+-%-${width2}s-+-%-${width3}s-+-%-${width4}s\n" \
        "$(printf -- '-%.0s' $(seq 1 $width1))" \
        "$(printf -- '-%.0s' $(seq 1 $width2))" \
        "$(printf -- '-%.0s' $(seq 1 $width3))" \
        "$(printf -- '-%.0s' $(seq 1 $width4))"

    for ((i = 1; i < ${#rows[@]}; i++)); do
        IFS='|' read -r c1 c2 c3 c4 <<< "${rows[i]}"
        printf "%-${width1}s | %-${width2}s | %-${width3}s | %-${width4}s\n" "$c1" "$c2" "$c3" "$c4"
    done
}

if [[ "${ONLY_PATCH_ANNOTATE}" == true ]]; then
  print_table "Others" "${others_rows[@]}"
else
  print_table "Emissary" "${emissary_rows[@]}"
  print_table "Websocket" "${websocket_rows[@]}"
  print_table "Others" "${others_rows[@]}"
fi