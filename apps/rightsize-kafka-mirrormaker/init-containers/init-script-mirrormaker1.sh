#!/bin/bash

# exit immediately if a command exits with a non-zero status.
set -e

# No escaping needed. This is a clean script.
SCRIPT_NAME="$(basename "$0")"

log() {
    local -r level="$1"
    local -r message="$2"
    local timestamp
    timestamp=$(date +"%Y-%m-%d %H:%M:%S")

    # using printf for reliable, consistent formatting.
    printf "%s [%s] [%s] %s\n" "$timestamp" "$level" "$SCRIPT_NAME" "$message" >&2
}

log_info() {
    local -r message="$1"
    log "INFO" "$message"
}

log_warn() {
    local -r message="$1"
    log "WARN" "$message"
}

log_error() {
    local -r message="$1"
    log "ERROR" "$message"
}

assert_is_installed() {
    if ! which "$1" >/dev/null 2>&1; then
        log_error "the binary '$1' (checked with 'which') is required but is not installed or in the system's PATH."
        exit 1
    fi
}

assert_is_file_exist() {
    if [[ ! -f "$1" ]]; then
        log_error "the file '$1' is required but is not exist or is not have required permission."
        exit 1
    fi
}

assert_is_value_exist() {
    local -r var_name="$1"
    if [[ -z "${!var_name}" ]]; then
        log_error "the environment variable '$var_name' is required but is not set or has an empty value."
        exit 1
    fi
}

assert_tcp_connection_to_brokers() {
    local -r bootstrap_servers="$1"
    local -r timeout_seconds=3

    echo "$bootstrap_servers" | tr ',' '\n' | while read -r broker_pair; do
        if [ -z "$broker_pair" ]; then
            continue
        fi

        local host
        local port

        host=$(echo "$broker_pair" | cut -d: -f1)
        port=$(echo "$broker_pair" | cut -d: -f2)

        if [ -z "$host" ] || [ -z "$port" ]; then
            log_error "could not parse broker string: '$broker_pair'"
            exit 1
        fi

        #    use netcat (nc) to perform the TCP check
        #    -z: zero-I/O mode (scan only, don't send data)
        #    -w: wait timeout
        #    redirect stdout/stderr to /dev/null to be silent on success
        if ! nc -z -w $timeout_seconds "$host" "$port" >/dev/null 2>&1; then
            log_error "tcp connection check failed for broker: $host:$port. check network ACLs, security groups, or firewalls."
            exit 1
        fi
    done
}

assert_is_kafka_sasl_scram_creds_valid() {
    local -r bootstrap_servers="$1"
    local -r sasl_username="$2"
    local -r sasl_password="$3"
    local -r sasl_mechanism="SCRAM-SHA-512"
    local -r security_protocol="SASL_SSL"
    
    local -r sasl_opts="-X security.protocol=$security_protocol -X sasl.mechanism=$sasl_mechanism -X sasl.username=$sasl_username -X sasl.password=$sasl_password"

    # add a 10-second timeout to prevent hangs
    # pipe stderr to /dev/null to suppress kcat's own errors
    if ! timeout 10s kcat -L -b "$bootstrap_servers" $sasl_opts 2>/dev/null | grep -q "__consumer_offsets"; then
        log_error "failed to validate kafka credentials for '$sasl_username' at '$bootstrap_servers'. check connectivity, firewall rules, and credentials."
        exit 1
    fi
}

assert_is_valid_offset_policy() {
    local -r var_name="$1"
    local -r var_value="${!var_name}"

    if [[ "$var_value" != "earliest" && "$var_value" != "latest" ]]; then
        log_error "the environment variable '$var_name' has an invalid value: '$var_value'. It must be one of 'earliest' or 'latest'."
        exit 1
    fi

}

generate_properties_files() {
    log_info "generating MM1 properties files using sed..."
    
    # copy templates from read-only volume to writable shared volume
    cp /config-template/consumer.properties /shared-config/consumer.properties
    cp /config-template/producer.properties /shared-config/producer.properties
    
    # --- robust sed replacement ---
    # we must escape special characters in the variables (\, &, and our delimiter |)
    # before passing them to sed to prevent the command from breaking.
    local -r S_BOOTSTRAP=$(echo "$SOURCE_MSK_BOOTSTRAP_SERVERS" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g')
    local -r S_USER=$(echo "$SOURCE_MSK_SASL_USERNAME" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g')
    local -r S_PASS=$(echo "$SOURCE_MSK_SASL_PASSWORD" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g')
    
    local -r T_BOOTSTRAP=$(echo "$TARGET_MSK_BOOTSTRAP_SERVERS" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g')
    local -r T_USER=$(echo "$TARGET_MSK_SASL_USERNAME" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g')
    local -r T_PASS=$(echo "$TARGET_MSK_SASL_PASSWORD" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g')
    
    # this variable is safe, no special chars expected
    local -r OFFSET_POLICY="$MM1_OFFSET_RESET_POLICY"

    # use sed -i to edit the files in-place in the /shared-config directory
    # we use | as the delimiter for s/find/replace/g
    if ! sed -i "s|__SOURCE_MSK_BOOTSTRAP_SERVERS__|$S_BOOTSTRAP|g" /shared-config/consumer.properties; then
        log_error "sed substitution failed for __SOURCE_MSK_BOOTSTRAP_SERVERS__"
        exit 1
    fi

    if ! sed -i "s|__SOURCE_MSK_SASL_USERNAME__|$S_USER|g" /shared-config/consumer.properties; then
        log_error "sed substitution failed for __SOURCE_MSK_SASL_USERNAME__"
        exit 1
    fi

    if ! sed -i "s|__SOURCE_MSK_SASL_PASSWORD__|$S_PASS|g" /shared-config/consumer.properties; then
        log_error "sed substitution failed for __SOURCE_MSK_SASL_PASSWORD__"
        exit 1
    fi

    if ! sed -i "s|__MM1_OFFSET_RESET_POLICY__|$OFFSET_POLICY|g" /shared-config/consumer.properties; then
        log_error "sed substitution failed for __MM1_OFFSET_RESET_POLICY__"
        exit 1
    fi

    if ! sed -i "s|__TARGET_MSK_BOOTSTRAP_SERVERS__|$T_BOOTSTRAP|g" /shared-config/producer.properties; then
        log_error "sed substitution failed for __TARGET_MSK_BOOTSTRAP_SERVERS__"
        exit 1
    fi

    if ! sed -i "s|__TARGET_MSK_SASL_USERNAME__|$T_USER|g" /shared-config/producer.properties; then
        log_error "sed substitution failed for __TARGET_MSK_SASL_USERNAME__"
        exit 1
    fi

    if ! sed -i "s|__TARGET_MSK_SASL_PASSWORD__|$T_PASS|g" /shared-config/producer.properties; then
        log_error "sed substitution failed for __TARGET_MSK_SASL_PASSWORD__"
        exit 1
    fi

    log_info "successfully generated consumer.properties in /shared-config/"
    log_info "successfully generated producer.properties in /shared-config/"
    log_info "all properties files generated successfully."
}

main() {
    log_info "asserting tool dependencies..."
    assert_is_installed "date"
    assert_is_installed "grep"
    assert_is_installed "kcat"
    assert_is_installed "tr"
    assert_is_installed "nc"
    assert_is_installed "cut"
    assert_is_installed "basename"
    assert_is_installed "timeout"
    assert_is_installed "sed"

    log_info "asserting file dependencies..."
    assert_is_file_exist "/config-template/producer.properties"
    assert_is_file_exist "/config-template/consumer.properties"

    log_info "asserting environment variables..."
    assert_is_value_exist "TOPIC_WHITELIST"
    assert_is_value_exist "NUM_STREAMS"
    assert_is_value_exist "SOURCE_MSK_BOOTSTRAP_SERVERS"
    assert_is_value_exist "SOURCE_MSK_SASL_USERNAME"
    assert_is_value_exist "SOURCE_MSK_SASL_PASSWORD"
    assert_is_value_exist "TARGET_MSK_BOOTSTRAP_SERVERS"
    assert_is_value_exist "TARGET_MSK_SASL_USERNAME"
    assert_is_value_exist "TARGET_MSK_SASL_PASSWORD"
    assert_is_value_exist "OFFSET_RESET_POLICY"

    assert_is_valid_offset_policy "OFFSET_RESET_POLICY"

    log_info "validating source msk connection..."
    assert_tcp_connection_to_brokers "$SOURCE_MSK_BOOTSTRAP_SERVERS"
    assert_is_kafka_sasl_scram_creds_valid \
        "$SOURCE_MSK_BOOTSTRAP_SERVERS" \
        "$SOURCE_MSK_SASL_USERNAME" \
        "$SOURCE_MSK_SASL_PASSWORD"

    log_info "validating target msk connection..."
    assert_tcp_connection_to_brokers "$TARGET_MSK_BOOTSTRAP_SERVERS"
    assert_is_kafka_sasl_scram_creds_valid \
        "$TARGET_MSK_BOOTSTRAP_SERVERS" \
        "$TARGET_MSK_SASL_USERNAME" \
        "$TARGET_MSK_SASL_PASSWORD"

    # export variables so sed can find them
    export SOURCE_MSK_BOOTSTRAP_SERVERS
    export SOURCE_MSK_SASL_USERNAME
    export SOURCE_MSK_SASL_PASSWORD
    export MM1_OFFSET_RESET_POLICY
    export TARGET_MSK_BOOTSTRAP_SERVERS
    export TARGET_MSK_SASL_USERNAME
    export TARGET_MSK_SASL_PASSWORD

    generate_properties_files

    log_info "init container finished successfully. handing over to main container."
}

main "$@"

