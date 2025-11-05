#!/usr/bin/env bash

set -e

SCRIPT_NAME="$(basename "$0")"

print_usage() {
    echo
    echo "Usage: $SCRIPT_NAME [OPTIONS]"
    echo
    echo "This script is used to setup and run MirrorMaker 2 binary using systemd."
    echo
    echo "Example: "
    echo "  Bootstrap MirrorMaker 2: "
    echo "  $SCRIPT_NAME"
    echo
    echo
}

log() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date +"%Y-%m-%d %H:%M:%S")
    echo >&2 -e "${timestamp} [${level}] [$SCRIPT_NAME] ${message}"
}

log_info() {
    local message="$1"
    log "INFO" "$message"
}

log_warn() {
    local message="$1"
    log "WARN" "$message"
}

log_error() {
    local message="$1"
    log "ERROR" "$message"
}

assert_is_installed() {
    local -r name="$1"

    if ! command -v "${name}" >/dev/null 2>&1; then
        log_error "The binary '$name' is required by this script but is not installed or in the system's PATH."
        return 1
    else
        log_info "The binary '$name' is installed."
    fi
}

generate_mm2_systemd() {
    cat <<EOF >"/etc/systemd/system/mirrormaker2.service"
[Unit]
Description=Apache Kafka MirrorMaker 2
After=network.target zookeeper.service kafka.service

[Service]
Type=simple
Environment="KAFKA_LOG4J_OPTS=-Dlog4j.configuration=file:/mm2-log4j.properties"
Environment="PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/snap/bin:/jdk-11.0.2/bin"
ExecStart=/kafka_2.13-3.7.2/bin/connect-mirror-maker.sh /mm2.properties
Restart=on-failure
RestartSec=5s
# Optional: Increase the limit for open files, which can be an issue for Kafka
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
}

generate_mm2_log4j_properties() {
    cat <<EOF >"/mm2-log4j.properties"
log4j.rootLogger=INFO, stdout, mm2Appender

log4j.appender.stdout=org.apache.log4j.ConsoleAppender
log4j.appender.stdout.layout=org.apache.log4j.PatternLayout
log4j.appender.stdout.layout.ConversionPattern=[%d] %p %m (%c)%n

log4j.appender.mm2Appender=org.apache.log4j.DailyRollingFileAppender
log4j.appender.mm2Appender.DatePattern='.'yyyy-MM-dd-HH
log4j.appender.mm2Appender.File=/var/log/kafka/mirrormaker2/mirrormaker2.log
log4j.appender.mm2Appender.layout=org.apache.log4j.PatternLayout
log4j.appender.mm2Appender.layout.ConversionPattern=[%d] %p %m (%c)%n

# Adjust log levels as needed
log4j.logger.org.apache.kafka.connect.mirror=INFO
log4j.logger.org.apache.kafka.clients=WARN
EOF
}

generate_mm2_properties() {
    cat <<EOF >"/mm2.properties"
clusters = source, target
source.bootstrap.servers = SOURCE_KAFKA_URL
target.bootstrap.servers = TARGET_KAFKA_URL

source.consumer.auto.offset.reset=latest
auto.offset.reset=latest

# source.security.protocol = PLAINTEXT
# source.sasl.mechanism = PLAIN
# source.sasl.jaas.config = org.apache.kafka.common.security.plain.PlainLoginModule required username="SOURCE_KAFKA_USERNAME" password="SOURCE_KAFKA_PASSWORD";

target.security.protocol = SASL_SSL
target.sasl.mechanism = SCRAM-SHA-512
target.sasl.jaas.config = org.apache.kafka.common.security.scram.ScramLoginModule required username="TARGET_KAFKA_USERNAME" password="TARGET_KAFKA_PASSWORD";

mirrors = source->target
source->target.enabled = true

# disable the source cluster alias prefix
source->target.replication.policy.class=org.apache.kafka.connect.mirror.IdentityReplicationPolicy

topics = .*
groups = .*

# set this to "true" to enable ConsumerGroup offset synchronization
sync.group.offsets.enabled = false
sync.group.offsets.interval.seconds = 60

checkpoints.topic.replication.factor = 2
heartbeats.topic.replication.factor = 2
offset.syncs.topic.replication.factor = 2

emit.checkpoints.interval.seconds = 5

offset.storage.replication.factor = 2
status.storage.replication.factor = 2
config.storage.replication.factor = 2
EOF
}

configure_mm2_properties() {
    local source_kafka_url
    local target_kafka_url

    if ! aws ssm get-parameter --name "/test/source_kafka_url" --region eu-west-1 --with-decryption --no-cli-pager >/dev/null; then
        log_error "unable to retrieve ssm parameter"
        return 1
    fi

    if ! aws ssm get-parameter --name "/eks/starbreeze/justice/pd3/kafka/justice_brokers" --region eu-west-1 --with-decryption --no-cli-pager >/dev/null; then
        log_error "unable to retrieve ssm parameter"
        return 1
    fi

    if ! aws ssm get-parameter --name "/eks/starbreeze/justice/pd3/kafka/justice_sasl_username" --region eu-west-1 --with-decryption --no-cli-pager >/dev/null; then
        log_error "unable to retrieve ssm parameter"
        return 1
    fi

    if ! aws ssm get-parameter --name "/eks/starbreeze/justice/pd3/kafka/justice_sasl_password" --region eu-west-1 --with-decryption --no-cli-pager >/dev/null; then
        log_error "unable to retrieve ssm parameter"
        return 1
    fi

    source_kafka_url="$(aws ssm get-parameter --name "/rst-83/sbz_pd3_prod_non_iac_kafka_url" --region eu-west-1 --with-decryption --no-cli-pager | jq -r '.Parameter.Value')"
    target_kafka_url="$(aws ssm get-parameter --name "/eks/starbreeze/justice/pd3/kafka/justice_brokers" --region eu-west-1 --with-decryption --no-cli-pager | jq -r '.Parameter.Value')"
    target_kafka_username="$(aws ssm get-parameter --name "/eks/starbreeze/justice/pd3/kafka/justice_sasl_username" --region eu-west-1 --with-decryption --no-cli-pager | jq -r '.Parameter.Value')"
    target_kafka_password="$(aws ssm get-parameter --name "/eks/starbreeze/justice/pd3/kafka/justice_sasl_password" --region eu-west-1 --with-decryption --no-cli-pager | jq -r '.Parameter.Value')"

    sed -i "s/SOURCE_KAFKA_URL/$source_kafka_url/g" /mm2.properties
    sed -i "s/TARGET_KAFKA_URL/$target_kafka_url/g" /mm2.properties
    sed -i "s/TARGET_KAFKA_USERNAME/$target_kafka_username/g" /mm2.properties
    sed -i "s/TARGET_KAFKA_PASSWORD/$target_kafka_password/g" /mm2.properties
}

run_mm2_in_background() {
    log_info "running systemctl daemon-reload"
    systemctl daemon-reload

    log_info "running systemctl enable mirrormaker2.service"
    systemctl enable mirrormaker2.service

    log_info "running systemctl start mirrormaker2.service"
    systemctl start mirrormaker2.service
}

run() {
    generate_mm2_properties
    generate_mm2_log4j_properties
    generate_mm2_systemd
    configure_mm2_properties
    run_mm2_in_background
}

main() {

    while [[ $# -gt 0 ]]; do
        local key="$1"

        case "$key" in
        -h)
            print_usage
            exit
            ;;
        --help)
            print_usage
            exit
            ;;
        *)
            log_error "Unrecognized argument: $key"
            print_usage
            exit 1
            ;;
        esac
    done

    if [[ "$(whoami)" != "root" ]]; then
        log_error "$SCRIPT_NAME should be run as sudoers or root"
        exit 1
    fi

    export PATH=$PATH:/jdk-11.0.2/bin:/kafka_2.13-3.7.2/bin

    # required binaries
    REQUIRED_BINARIES=(
        "curl"
        "unzip"
        "aws"
        "java"
        "connect-mirror-maker.sh"
        "jq"
    )

    IS_ERROR="false"

    for REQUIRED_BINARY in "${REQUIRED_BINARIES[@]}"; do
        if ! assert_is_installed "$REQUIRED_BINARY"; then
            IS_ERROR="true"
        fi
    done

    if [[ "$IS_ERROR" != "false" ]]; then
        exit 1
    fi

    run
}

main "$@"
