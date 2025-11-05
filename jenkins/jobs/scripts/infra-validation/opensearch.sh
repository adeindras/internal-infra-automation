#!/bin/bash
if [[ -z ${EXPECTED_INSTANCE_TYPE} ]]; then
  echo "EXPECTED_INSTANCE_TYPE must be specified"
  exit 1
fi

if [[ -z ${OUTPUT_FILE} ]]; then
  echo "OUTPUT_FILE must be specified"
  exit 1
fi

openSearchId=$(terragrunt show -json | jq -r '.values.outputs.es_arn.value' | cut -d "/" -f 2)
provisionedInstanceClass=$(aws opensearch describe-domain --domain-name ${openSearchId} --no-cli-pager --output text --query 'DomainStatus.ClusterConfig.InstanceType')

isMatch="false"
outputNote=""
if [[ "${provisionedInstanceClass}" == "${EXPECTED_INSTANCE_TYPE}" ]]; then
  outputNote="Match ${EXPECTED_INSTANCE_TYPE}"
  isMatch="true"
else
  outputNote="Mismatch! expecting ${EXPECTED_INSTANCE_TYPE} but got ${provisionedInstanceClass}"
fi
echo ${outputNote}

cat ${OUTPUT_FILE} | jq --arg resourceIdentifier "${openSearchId}" \
  --arg expectedInstanceClass "${EXPECTED_INSTANCE_TYPE}" \
  --arg provisionedInstanceClass "${provisionedInstanceClass}" \
  --arg outputNote "${outputNote}" \
  --arg validationStatus "${isMatch}" '.validationOutput += [
  {
    "resourceType": "Opensearch",
    "resourceIdentifier": $resourceIdentifier,
    "expectedInstanceClass": $expectedInstanceClass,
    "provisionedInstanceClass": $provisionedInstanceClass,
    "validationStatus": $validationStatus,
    "note": $outputNote
  }
]' > ${OUTPUT_FILE}.tmp
mv ${OUTPUT_FILE}.tmp ${OUTPUT_FILE}

  