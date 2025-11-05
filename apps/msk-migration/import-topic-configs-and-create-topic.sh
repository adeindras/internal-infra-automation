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

TOPIC_CONFIG_CSV_FILE="$1"
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

if [ -z "$TOPIC_CONFIG_CSV_FILE" ]; then
    echo "\$TOPIC_CONFIG_CSV_FILE is empty"
    echo "please provide the topic config csv file as the command argument:"
    echo "$0 <csv-file-name>"
    exit 1
fi

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
{
    read -r header

    echo "$header" > /dev/null

    while IFS=',' read -r topic_name partition_count replication_factor min_insync_replicas cleanup_policy retention_ms message_format_version unclean_leader_election_enable
    do

        # TODO: add input validation for every variable

        if [[ "${cleanup_policy}" == "undefined" ]]; then
            $KAFKA_TOPICS_SH --bootstrap-server $BOOTSTRAP_SERVER --command-config "$CLIENT_PROPERTIES_FILE" \
                --create --topic "${topic_name}" --partitions "${partition_count}" --replication-factor "${replication_factor}" \
                --config min.insync.replicas="${min_insync_replicas}" \
                --config retention.ms="${retention_ms}" \
                --config message.format.version="${message_format_version}" \
                --config unclean.leader.election.enable="${unclean_leader_election_enable}"
        else
            $KAFKA_TOPICS_SH --bootstrap-server $BOOTSTRAP_SERVER --command-config "$CLIENT_PROPERTIES_FILE" \
                --create --topic "${topic_name}" --partitions "${partition_count}" --replication-factor "${replication_factor}" \
                --config min.insync.replicas="${min_insync_replicas}" \
                --config cleanup.policy="${cleanup_policy}" \
                --config retention.ms="${retention_ms}" \
                --config message.format.version="${message_format_version}" \
                --config unclean.leader.election.enable="${unclean_leader_election_enable}"
        fi
    done
} < "$TOPIC_CONFIG_CSV_FILE"