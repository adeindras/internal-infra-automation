#!/bin/bash

set -euo pipefail

trap err ERR

err () {
  echo "The script is encountering an error on line $(caller)." >&2
  code=$?
  kubectl delete -n default -k jumpbox/manifests
  exit $code
}

brokerList=$1
podName="jumpbox-msk-upgrade"
topicsWithLessThan2RF="0"
topicsWithMinISRNot1="0"
faultyTopics=""

kubectl create -n default -k jumpbox/manifests
kubectl wait -n default --for=condition=Ready pod/$podName --timeout=300s

topicList=$(kubectl exec -n default $podName -- /srv/kafka/bin/kafka-topics.sh --bootstrap-server $brokerList --list --command-config /srv/kafka-properties/ssl-user-config.properties)
topicDescription=$(kubectl exec -n default $podName -- /srv/kafka/bin/kafka-topics.sh --bootstrap-server $brokerList --describe --command-config /srv/kafka-properties/ssl-user-config.properties | grep 'TopicId')

resultFileName="result-${CUSTOMER_NAME}-${PROJECT}-${ENVIRONMENT_NAME}-$(date +%s).csv"

echo "TopicName,ReplicationFactor,minISR,group" > "${resultFileName}"

while read -r description; do
  # Get topic name
  topic=$(sed 's#.*Topic: \(.\+\).*TopicId.*#\1#' <<< "$description" | tr -d '[:blank:]')
  echo $topic
   if [[ $topic != "__amazon_msk_canary" && $topic != "__consumer_offsets" ]]; then
     # Get Replication Factor
     replicationFactor=$(grep ReplicationFactor <<< "$description" | sed 's#.*ReplicationFactor: \([0-9]\+\).*#\1#')

     if (( $replicationFactor < 2)); then
       ((++topicsWithLessThan2RF))
     fi

     # Get minimum ISR
     minimumISR=$(grep Configs <<< "$description" | sed 's#.*Configs:.*min\.insync\.replicas=\([0-9]\+\),.*#\1#')

     if (( $minimumISR != 1)); then
       ((++topicsWithMinISRNot1))
     fi

     if (( $replicationFactor < 2 || $minimumISR != 1)); then
       faultyTopics+="$topic"$'\n'
       group="ags-core"
       if [[ "$topic" == *"analytics"* ]]; then
         group="analytics"
       fi
       echo "$topic,$replicationFactor,$minimumISR,$group" >> "${resultFileName}"
     fi

     echo ""
     echo "---"
     echo "Topic name: ${topic}"
     echo "Replication factor: ${replicationFactor}"
     echo "Minimum ISR: ${minimumISR}"
   fi
done <<< $topicDescription

echo "---"
echo "Topics to check: $faultyTopics"
echo "Topics with replication factor < 2: $topicsWithLessThan2RF topic(s)"
echo "Topics with minimumISR != 1: $topicsWithMinISRNot1 topic(s)"

kubectl delete -n default -k jumpbox/manifests
