#!/bin/bash
if [[ -z ${EXPECTED_INSTANCE_TYPE} ]]; then
  echo "EXPECTED_INSTANCE_TYPE must be specified"
  exit 1
fi

if [[ -z ${OUTPUT_FILE} ]]; then
  echo "OUTPUT_FILE must be specified"
  exit 1
fi

docDbARN=$(terragrunt show -json | jq -r '.values.outputs.docdb_arn.value')

while read line; do 
  isMatch="false"
  outputNote=""
  provisionedInstanceClass=$(aws docdb describe-db-instances --db-instance-identifier ${line} --query "DBInstances[*].DBInstanceClass" --output text --no-cli-pager)
  if [[ "${provisionedInstanceClass}" == "${EXPECTED_INSTANCE_TYPE}" ]]; then
    outputNote="Match ${EXPECTED_INSTANCE_TYPE}"
    isMatch="true"
  else
    outputNote="Mismatch! expecting ${EXPECTED_INSTANCE_TYPE} but got ${provisionedInstanceClass}"
  fi
  echo ${outputNote}

  cat ${OUTPUT_FILE} | jq --arg resourceIdentifier "${line}" \
    --arg expectedInstanceClass "${EXPECTED_INSTANCE_TYPE}" \
    --arg provisionedInstanceClass "${provisionedInstanceClass}" \
    --arg outputNote "${outputNote}" \
    --arg validationStatus "${isMatch}" '.validationOutput += [
    {
      "resourceType": "DOCUMENT DB",
      "resourceIdentifier": $resourceIdentifier,
      "expectedInstanceClass": $expectedInstanceClass,
      "provisionedInstanceClass": $provisionedInstanceClass,
      "validationStatus": $validationStatus,
      "note": $outputNote
    }
  ]' > ${OUTPUT_FILE}.tmp
  mv ${OUTPUT_FILE}.tmp ${OUTPUT_FILE}

done < <(aws docdb describe-db-clusters --db-cluster-identifier ${docDbARN##*:} --output text --no-cli-pager | grep DBCLUSTERMEMBERS | awk '{print $3}')
