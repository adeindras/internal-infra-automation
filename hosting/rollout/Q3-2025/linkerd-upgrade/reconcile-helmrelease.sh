#!/bin/bash

set -euo pipefail

# Configuration
CRD_CHART_VERSION="${CRD_CHART_VERSION:-1.8.0}"  # Set via env var
CONTROL_PLANE_CHART_VERSION="${CONTROL_PLANE_CHART_VERSION:-1.16.11}"  # Set via env var
NAMESPACE="${LINKERD_NAMESPACE:-linkerd}"
CRD_RELEASE_NAME="${CRD_RELEASE_NAME:-linkerd-crds}"
CONTROL_PLANE_RELEASE_NAME="${CONTROL_PLANE_RELEASE_NAME:-linkerd-control-plane}"
MAX_WAIT_TIME=600  # 5 minutes timeout

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if required tools are available
check_dependencies() {
    local deps=("kubectl" "flux")
    for dep in "${deps[@]}"; do
        if ! command -v "$dep" &> /dev/null; then
            log_error "$dep is not installed or not in PATH"
            exit 1
        fi
    done
    log_info "All required dependencies are available"
}

# Get current version of a HelmRelease
get_helmrelease_version() {
    local release_name="$1"
    local namespace="$2"
    
    kubectl get helmrelease "$release_name" -n "$namespace" \
        -o jsonpath='{.spec.chart.spec.version}' 2>/dev/null || echo "not-found"
}

# Get HelmRelease ready status
get_helmrelease_status() {
    local release_name="$1"
    local namespace="$2"
    
    kubectl get helmrelease "$release_name" -n "$namespace" \
        -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown"
}

# Wait for HelmRelease to be ready
wait_for_helmrelease() {
    local release_name="$1"
    local namespace="$2"
    local timeout="$3"
    
    log_info "Waiting for $release_name to be ready (timeout: ${timeout}s)"
    
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local status=$(get_helmrelease_status "$release_name" "$namespace")
        
        if [ "$status" = "True" ]; then
            log_info "$release_name is ready"
            return 0
        elif [ "$status" = "False" ]; then
            log_warn "$release_name reconciliation failed, checking again in 10s..."
        else
            log_info "$release_name status: $status, waiting..."
        fi
        
        sleep 10
        elapsed=$((elapsed + 10))
    done
    
    log_error "Timeout waiting for $release_name to be ready"
    return 1
}

# Reconcile a HelmRelease
reconcile_helmrelease() {
    local release_name="$1"
    local namespace="$2"
    
    log_info "Reconciling HelmRelease: $release_name"
    
    if ! flux reconcile helmrelease "$release_name" -n "$namespace"; then
        log_error "Failed to trigger reconciliation for $release_name"
        return 1
    fi
    
    # Wait for reconciliation to complete
    if ! wait_for_helmrelease "$release_name" "$namespace" "$MAX_WAIT_TIME"; then
        log_error "HelmRelease $release_name failed to reconcile successfully"
        return 1
    fi
    
    log_info "Successfully reconciled $release_name"
    return 0
}

# Check and upgrade a HelmRelease if needed
check_and_upgrade_helmrelease() {
    local release_name="$1"
    local namespace="$2"
    local expected_version="$3"
    
    log_info "Checking $release_name version..."
    
    local current_version=$(get_helmrelease_version "$release_name" "$namespace")
    
    if [ "$current_version" = "not-found" ]; then
        log_error "HelmRelease $release_name not found in namespace $namespace"
        return 1
    fi
    
    log_info "Current version: $current_version"
    log_info "Expected version: $expected_version"
    
    if [ "$current_version" != "$expected_version" ]; then
        log_warn "$release_name is not on the expected version, reconciling..."
        if ! reconcile_helmrelease "$release_name" "$namespace"; then
            return 1
        fi
        
        # Verify version after reconciliation
        local new_version=$(get_helmrelease_version "$release_name" "$namespace")
        log_info "Version after reconciliation: $new_version"
        
        if [ "$new_version" != "$expected_version" ]; then
            log_warn "Version mismatch after reconciliation. This might be expected if the HelmRelease spec hasn't been updated to the target version."
        fi
    else
        log_info "$release_name is already on the expected version"
        
        # Check if it's ready even if version matches
        local status=$(get_helmrelease_status "$release_name" "$namespace")
        if [ "$status" != "True" ]; then
            log_warn "$release_name is not ready, reconciling..."
            reconcile_helmrelease "$release_name" "$namespace"
        fi
    fi
    
    return 0
}

# Main upgrade process
main() {
    log_info "Starting Linkerd upgrade automation"
    log_info "CRD Chart version: $CRD_CHART_VERSION"
    log_info "Control Plane Chart version: $CONTROL_PLANE_CHART_VERSION"
    log_info "Namespace: $NAMESPACE"
    
    # Check dependencies
    check_dependencies
    
    # Step 1: Check and upgrade linkerd-crds first (dependency)
    log_info "=== Processing linkerd-crds (dependency) ==="
    if ! check_and_upgrade_helmrelease "$CRD_RELEASE_NAME" "$NAMESPACE" "$CRD_CHART_VERSION"; then
        log_error "Failed to process linkerd-crds"
        exit 1
    fi
    
    # Step 2: Check and upgrade linkerd-control-plane
    log_info "=== Processing linkerd-control-plane ==="
    if ! check_and_upgrade_helmrelease "$CONTROL_PLANE_RELEASE_NAME" "$NAMESPACE" "$CONTROL_PLANE_CHART_VERSION"; then
        log_error "Failed to process linkerd-control-plane"
        exit 1
    fi
    
    # Final verification
    log_info "=== Final Status Check ==="
    local crd_status=$(get_helmrelease_status "$CRD_RELEASE_NAME" "$NAMESPACE")
    local cp_status=$(get_helmrelease_status "$CONTROL_PLANE_RELEASE_NAME" "$NAMESPACE")
    
    log_info "linkerd-crds status: $crd_status"
    log_info "linkerd-control-plane status: $cp_status"
    
    if [ "$crd_status" = "True" ] && [ "$cp_status" = "True" ]; then
        log_info "✅ Linkerd upgrade automation completed successfully!"
    else
        log_error "❌ Some components are not ready. Check the HelmRelease status manually."
        exit 1
    fi
}

# Handle script arguments
case "${1:-}" in
    --help|-h)
        echo "Linkerd Upgrade Automation Script"
        echo ""
        echo "Environment Variables:"
        echo "  CRD_CHART_VERSION         - Target CRD chart version (default: 1.8.0)"
        echo "  CONTROL_PLANE_CHART_VERSION - Target control plane chart version (default: 1.16.11)"
        echo "  LINKERD_NAMESPACE         - Linkerd namespace (default: linkerd)"
        echo "  CRD_RELEASE_NAME          - CRD HelmRelease name (default: linkerd-crds)"
        echo "  CONTROL_PLANE_RELEASE_NAME - Control plane HelmRelease name (default: linkerd-control-plane)"
        echo ""
        echo "Usage: $0 [--help]"
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac