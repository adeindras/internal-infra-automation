#!/bin/bash
if [[ -z ${EXPECTED_INSTANCE_TYPE} ]]; then
  echo "EXPECTED_INSTANCE_TYPE must be specified"
  exit 1
fi

if [[ -z ${OUTPUT_FILE} ]]; then
  echo "OUTPUT_FILE must be specified"
  exit 1
fi

rdsARN=$(terragrunt show -json | jq -r '.values.outputs.db_instance_arn.value')
provisionedInstanceClass=$(aws rds describe-db-instances --db-instance-identifier ${rdsARN##*:} --query "DBInstances[*].DBInstanceClass" --output text --no-cli-pager)

isMatch="false"
outputNote=""
if [[ "${provisionedInstanceClass}" == "${EXPECTED_INSTANCE_TYPE}" ]]; then 
  outputNote="Match ${provisionedInstanceClass}"
  isMatch="true"
else
  outputNote="Mismatch! expecting ${EXPECTED_INSTANCE_TYPE} but got ${provisionedInstanceClass}"
fi
echo ${outputNote}

cat ${OUTPUT_FILE} | jq --arg resourceIdentifier "${rdsARN##*:}" \
  --arg expectedInstanceClass "${EXPECTED_INSTANCE_TYPE}" \
  --arg provisionedInstanceClass "${provisionedInstanceClass}" \
  --arg outputNote "${outputNote}" \
  --arg validationStatus "${isMatch}" '.validationOutput += [
  {
    "resourceType": "RDS",
    "resourceIdentifier": $resourceIdentifier,
    "expectedInstanceClass": $expectedInstanceClass,
    "provisionedInstanceClass": $provisionedInstanceClass,
    "validationStatus": $validationStatus,
    "note": $outputNote
  }
]' > ${OUTPUT_FILE}.tmp
mv ${OUTPUT_FILE}.tmp ${OUTPUT_FILE}