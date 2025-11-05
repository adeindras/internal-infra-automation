#!/usr/bin/env bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

# --- Bash Version Check ---

if [ -z "${BASH_VERSION}" ]; then
    echo "Error: This script requires Bash to run." >&2
    exit 1
fi

readonly BASH_MAJOR_VERSION=${BASH_VERSION%%.*}
readonly REQUIRED_BASH_VERSION=4

if [[ ${BASH_MAJOR_VERSION} -lt ${REQUIRED_BASH_VERSION} ]]; then
    echo "Error: This script requires Bash version ${REQUIRED_BASH_VERSION} or later to run." >&2
    echo "You are using Bash version ${BASH_VERSION}." >&2
    exit 1
fi

# --- Main Configuration ---

EXCLUDED_TOPICS=(
    "__consumer_offsets"
    "achievement"
    "<client-name>.<env-name>.entitlementConsumption"
    "<client-name>.<env-name>.entitlementManagement"
    "<client-name>.<env-name>.entitlementSale"
    "<client-name>.<env-name>.groupMember"
    "<client-name>.<env-name>.friendRequests"
)

KAFKA_HOME="/home/ubuntu/kafka_2.13-3.7.2"
BOOTSTRAP_SERVER="<broker-list>"
SASL_USERNAME="<sasl-username>"
SASL_PASSWORD="<sasl-password>"
KAFKA_TOPICS_SH="$KAFKA_HOME/bin/kafka-topics.sh"

if [ -z "$BOOTSTRAP_SERVER" ]; then
    echo "\$BOOTSTRAP_SERVER is empty"
    exit 1
fi

if [ -z "$SASL_USERNAME" ] || [ -z "$SASL_PASSWORD" ]; then
    echo "\$SASL_USERNAME and/or \$SASL_PASSWORD are empty"
    exit 1
fi

transform_array_to_escaped_pipe() {
    local result_string
    # build a string with the separator (\|) after each element.
    # the first backslash escapes the second one for the printf command.
    printf -v result_string '%s\\|' "$@"
    # remove the final trailing separator (\|) from the string.
    echo "${result_string%\\|}"
}

get_config_value() {
    local config_string="$1"
    local key_name="$2"
    local value
    value=$(echo "$config_string" | tr ',' '\n' | grep "^${key_name}=" | cut -d'=' -f2-)

    if [[ -n "$value" ]]; then
      echo "$value"
    else
      echo "key not exist"
    fi
}

excluded_grep_string=$(transform_array_to_escaped_pipe "${EXCLUDED_TOPICS[@]}")

# --- Create Temporary Client Properties File ---
# create a secure temporary file to hold the client configuration.
CLIENT_PROPERTIES_FILE=$(mktemp)

# this function will be called on script exit to ensure the temp file is deleted.
cleanup() {
    rm -f "$CLIENT_PROPERTIES_FILE"
}

trap cleanup EXIT

# write the necessary SASL configuration to the temporary file.
cat > "$CLIENT_PROPERTIES_FILE" << EOF
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="${SASL_USERNAME}" password="${SASL_PASSWORD}";
EOF

# --- Main Script Logic ---

TOPICS=$($KAFKA_TOPICS_SH --bootstrap-server $BOOTSTRAP_SERVER --command-config "$CLIENT_PROPERTIES_FILE" --list | grep -v "$excluded_grep_string")

if [ -z "$TOPICS" ]; then
    echo "No topics found on the cluster (or could not connect/authenticate)." >&2
    exit 1
fi

# print the CSV header.
echo "topic_name,partition_count,replication_factor,min_insync_replicas,cleanup_policy,retention_ms,message_format_version,unclean_leader_election_enable"

for TOPIC in $TOPICS; do

    # get a detailed information about kafka topic
    DESCRIBE_TOPIC_OUTPUT="$($KAFKA_TOPICS_SH --bootstrap-server "$BOOTSTRAP_SERVER" --command-config "$CLIENT_PROPERTIES_FILE" --describe --topic "$TOPIC")"

    TOPIC_NAME="$(echo "$DESCRIBE_TOPIC_OUTPUT" | grep "PartitionCount" | awk '{print $2}')"
    PARTITION_COUNT="$(echo "$DESCRIBE_TOPIC_OUTPUT" | grep "PartitionCount" | awk '{print $6}')"
    REPLICATION_FACTOR="$(echo "$DESCRIBE_TOPIC_OUTPUT" | grep "PartitionCount" | awk '{print $8}')"
    CONFIGS="$(echo "$DESCRIBE_TOPIC_OUTPUT" | grep "PartitionCount" | awk '{print $10}')"

    MIN_INSYNC_REPLICAS="$(get_config_value "$CONFIGS" "min.insync.replicas")"
    if [[ "$MIN_INSYNC_REPLICAS" == "key not exist" ]]; then
        MIN_INSYNC_REPLICAS="undefined"
    fi

    CLEANUP_POLICY="$(get_config_value "$CONFIGS" "cleanup.policy")"
    if [[ "$CLEANUP_POLICY" == "key not exist" ]]; then
        CLEANUP_POLICY="undefined"
    fi

    RETENTION_MS="$(get_config_value "$CONFIGS" "retention.ms")"
    if [[ "$RETENTION_MS" == "key not exist" ]]; then
        RETENTION_MS="undefined"
    fi

    MESSAGE_FORMAT_VERSION="$(get_config_value "$CONFIGS" "message.format.version")"
    if [[ "$MESSAGE_FORMAT_VERSION" == "key not exist" ]]; then
        MESSAGE_FORMAT_VERSION="undefined"
    fi

    UNCLEAN_LEADER_ELECTION_ENABLE="$(get_config_value "$CONFIGS" "unclean.leader.election.enable")"
    if [[ "$UNCLEAN_LEADER_ELECTION_ENABLE" == "key not exist" ]]; then
        UNCLEAN_LEADER_ELECTION_ENABLE="undefined"
    fi

    echo "$TOPIC_NAME,$PARTITION_COUNT,$REPLICATION_FACTOR,$MIN_INSYNC_REPLICAS,$CLEANUP_POLICY,$RETENTION_MS,$MESSAGE_FORMAT_VERSION,$UNCLEAN_LEADER_ELECTION_ENABLE"
done