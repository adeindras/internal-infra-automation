#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

echo "[+] Checking whether kafka-exporter is available or not ..."
get_available_kafka_exporter_pods=$(kubectl get deploy -n monitoring kafka-exporter-kafka -o custom-columns=AVAILABLE-REPLICAS:.status.availableReplicas --no-headers)

if [[ -z ${get_available_kafka_exporter_pods} ]] || [[ ${get_available_kafka_exporter_pods} -lt 1 ]]; then
    echo "No running kafka-exporter pods available. Terminating ..."
    exit 1
fi

echo "[+] Initiating port forward to kafka-exporter ..."
kubectl port-forward -n monitoring svc/kafka-exporter-kafka 9308:9308 &

while ! nc -vzw 2 127.0.0.1 9308 && [[ $((try++)) -lt 2 ]]; do sleep 3; done;

echo "[+] Obtaining list of Kafka topics and number of partitions"
curl -s http://localhost:9308/metrics | grep kafka_topic_partitions | prom2json | yq -I=1 -ojson '.[].metrics | {"rows": map({"resource_name": .labels.topic, "partitions": (.value | to_number) })}' > kafka_topic_partitions.json

echo "[+] Killing kubectl process ..."
pkill kubectl


