#!/bin/bash
if [[ -z ${EXPECTED_INSTANCE_TYPE} ]]; then
  echo "EXPECTED_INSTANCE_TYPE must be specified"
  exit 1
fi

if [[ -z ${OUTPUT_FILE} ]]; then
  echo "OUTPUT_FILE must be specified"
  exit 1
fi

redisId=$(terragrunt show -json | jq -r '.values.outputs.id.value')
replicationGroupMembers=$(aws elasticache describe-replication-groups --replication-group-id ${redisId} --no-cli-pager --output text --query 'ReplicationGroups[*].MemberClusters[]')

for instance in $(echo ${replicationGroupMembers}); do 
  isMatch="false"
  outputNote=""
  provisionedInstanceClass=$(aws elasticache describe-cache-clusters --cache-cluster-id ${instance} --no-cli-pager --output text --query 'CacheClusters[*].CacheNodeType')

  if [[ "${provisionedInstanceClass}" == "${EXPECTED_INSTANCE_TYPE}" ]]; then
    outputNote="Match ${EXPECTED_INSTANCE_TYPE}"
    isMatch="true"
  else
    outputNote="Mismatch! expecting ${EXPECTED_INSTANCE_TYPE} but got ${provisionedInstanceClass}"
  fi
  echo ${outputNote}

  cat ${OUTPUT_FILE} | jq --arg resourceIdentifier "${instance}" \
    --arg expectedInstanceClass "${EXPECTED_INSTANCE_TYPE}" \
    --arg provisionedInstanceClass "${provisionedInstanceClass}" \
    --arg outputNote "${outputNote}" \
    --arg validationStatus "${isMatch}" '.validationOutput += [
    {
      "resourceType": "Elasticache",
      "resourceIdentifier": $resourceIdentifier,
      "expectedInstanceClass": $expectedInstanceClass,
      "provisionedInstanceClass": $provisionedInstanceClass,
      "validationStatus": $validationStatus,
      "note": $outputNote
    }
  ]' > ${OUTPUT_FILE}.tmp
  mv ${OUTPUT_FILE}.tmp ${OUTPUT_FILE}
done