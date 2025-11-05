#!/bin/bash
if [[ -z ${OUTPUT_FILE} ]]; then
  echo "OUTPUT_FILE must be specified"
  exit 1
fi

if [[ -z ${EXPECTED_INSTANCE_TYPE} ]]; then
  echo "EXPECTED_INSTANCE_TYPE must be specified"
  exit 1
fi

if [[ -z ${DIRECTORY_NAME} ]]; then
  echo "DIRECTORY_NAME must be specified"
  exit 1
fi

if [[ -z ${RESOURCE_TYPE} ]]; then
  echo "RESOURCE_TYPE must be specified"
  exit 1
fi

outputNote="Directory for ${RESOURCE_TYPE}: ${DIRECTORY_NAME} not found!!"
isMatch="false"

cat ${OUTPUT_FILE} | jq --arg resourceType "${RESOURCE_TYPE}" \
    --arg resourceIdentifier "${resourceIdentifier}" \
    --arg expectedInstanceClass "${EXPECTED_INSTANCE_TYPE}" \
    --arg provisionedInstanceClass "${provisionedInstanceClass}" \
    --arg outputNote "${outputNote}" \
    --arg validationStatus "${isMatch}" '.validationOutput += [
    {
      "resourceType": $resourceType,
      "resourceIdentifier": $resourceIdentifier,
      "expectedInstanceClass": $expectedInstanceClass,
      "provisionedInstanceClass": $provisionedInstanceClass,
      "validationStatus": $validationStatus,
      "note": $outputNote
    }
  ]' > ${OUTPUT_FILE}.tmp
  mv ${OUTPUT_FILE}.tmp ${OUTPUT_FILE}