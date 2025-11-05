#!/usr/bin/env bash
# Copyright (c) 2022 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
set -e

function init(){
  readonly ORIG_CWD="$PWD"
  readonly OUTPUT_DIR=${ORIG_CWD}/output-db-check
  readonly TMP_FILE=${OUTPUT_DIR}/tmp.txt
  readonly SERVICES_JSON=${OUTPUT_DIR}/services.json
  readonly COLLECTION_TXT=${OUTPUT_DIR}/collections.txt
  readonly PG_TABLE_JSON=${OUTPUT_DIR}/pg_table.json
  readonly RESULT_JSON=${OUTPUT_DIR}/result.json

  mkdir -p ${OUTPUT_DIR}

  # AWS_ACCESS_KEY_ID=$(jq '.aws_access_key_id' ${CONFIG_FILE} | tr -d '"\r')
  # AWS_SECRET_ACCESS_KEY=$(jq '.aws_secret_access_key' ${CONFIG_FILE} | tr -d '"\r')
  # AWS_SESSION_TOKEN=$(jq '.aws_session_token' ${CONFIG_FILE} | tr -d '"\r')
  # AWS_REGION=$(jq '.aws_region' ${CONFIG_FILE} | tr -d '"\r')
  # AWS_CLUSTER=$(jq '.aws_cluster' ${CONFIG_FILE} | tr -d '"\r')
  MAIN_SSM_PATH="/eks/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}"
  MONGO_PORT="27017"
  POSTGRE_PORT="5432"
  MONGO_FORWARDER="justice-mongo-dev-forwarder"
  # LOCAL_PORT="80"
  LOCALHOST="127.0.0.1"

  if [ "${MONGO_MODE}" = "selfhosted" ]; then
      MONGO_USERNAME=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/mongo/mongodb_username --with-decryption | jq  '.Parameters[0].Value' | tr -d '"')
      MONGO_PASSWORD=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/mongo/mongodb_password --with-decryption | jq  '.Parameters[0].Value' | tr -d '"')
  elif [ "${MONGO_MODE}" = "docdb" ]; then
      MONGO_USERNAME=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/mongo/os_username --with-decryption | jq  '.Parameters[0].Value' | tr -d '"')
      MONGO_PASSWORD=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/mongo/os_password --with-decryption | jq  '.Parameters[0].Value' | tr -d '"')
      DOCDB_ADDR=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/mongo/os_address --with-decryption | jq  '.Parameters[0].Value' | tr -d '"')
  fi
  POSTGRE_USERNAME=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/postgres/postgresql16_username --with-decryption | jq  '.Parameters[0].Value' | tr -d '"')
  POSTGRE_PASSWORD=$(aws ssm get-parameters --region ${AWS_REGION} --names ${MAIN_SSM_PATH}/postgres/postgresql16_password --with-decryption | jq  '.Parameters[0].Value' | tr -d '"') 
}

function dependencies(){
  if ! hash jq 2>/dev/null; then
    echo "Require to install jq."
    exit 1
  fi
  if ! hash aws 2>/dev/null; then
    echo "Require to install aws."
    exit 1
  fi
  if ! hash kubectl 2>/dev/null; then
    echo "Require to install kubectl."
    exit 1
  fi
}

function runtime_duration() {
  time_complete=$(date +%s)
  runtime=$((time_complete-time_start))
  echo -e "\nCompleted in ${runtime} seconds"
}

function main(){
  source .env
  init
  dependencies
  time_start=$(date +%s)

  # export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
  # export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"
  # export AWS_SESSION_TOKEN="${AWS_SESSION_TOKEN}"
  jq -n "{db: []}" > ${RESULT_JSON}

  # update kubeconfig
  # aws eks --region ${AWS_REGION} update-kubeconfig --name ${AWS_CLUSTER}
  
  check_postgre
  # check_mongo

  # ./scripts/self-hosted-data-validation/killActivePort.sh
  runtime_duration
}

function check_postgre() {
  # port forward postgre
  export PGPASSWORD="${POSTGRE_PASSWORD}"
  kubectl port-forward -n postgresql svc/postgresql16 ${POSTGRE_PORT}:${POSTGRE_PORT} &
  
  sleep 15

  # psql -U ${POSTGRE_USERNAME} -h ${LOCALHOST} -d postgres \
  #   -c "SELECT ROW_TO_JSON(ROW(datname, pg_database_size(datname))) FROM pg_database" > ${TMP_FILE}
  # sed '/row_to_json/,+1d' ${TMP_FILE} | sed '/rows)/d' | tr -d '\r\t\n' | sed 's/} {/},{/g' | \
  #   sed 's/^/{"db":[/g' | sed 's/$/]}/g' | sed 's/f1/name/g' | sed 's/f2/size/g' | jq '.' > ${RESULT_JSON}

  # i=0
  # for DB_NAME in $(jq '.db[].name' ${RESULT_JSON} | tr -d '\r"'); do
  #   psql -U ${POSTGRE_USERNAME} -h ${LOCALHOST} -d ${DB_NAME} \
  #     -c "WITH tbl AS(SELECT table_schema,TABLE_NAME \
  #           FROM information_schema.tables \
  #           WHERE TABLE_NAME not like 'pg_%' \
  #           AND table_schema in ('public')) \
  #         SELECT TABLE_NAME,(xpath('/row/c/text()', query_to_xml(format('SELECT count(*) as c \
  #           FROM %I.%I', table_schema, TABLE_NAME), FALSE, TRUE, '')))[1]::text::int AS rows_n \
  #         FROM tbl ORDER BY rows_n DESC;" > ${TMP_FILE}
  #   sed '1,2d' ${TMP_FILE} | sed '/rows)/d' | tr -d ' ' | sed 's/|/,/g' | awk 'NF' | \
  #     jq -Rs 'split("\n") | map(if index(",") then split(",")|{(.[0]):.[1]} else empty end) | add' | jq -c '.' > ${PG_TABLE_JSON}
  #   DB_VAL=$(echo "{table:$(cat ${PG_TABLE_JSON} | tr -d '"')}")
  #   jq ".db[${i}] |= . + ${DB_VAL}" ${RESULT_JSON} > ${TMP_FILE} && cat ${TMP_FILE} > ${RESULT_JSON}
  #   i=$(( i+1 ))
  # done

  psql -U ${POSTGRE_USERNAME} -h ${LOCALHOST} -d 'postgres' \
    -c "SELECT datname,pg_database_size(datname) as db_size FROM pg_database" | sed '1,2d' | sed '/(*. rows)/d' | sed -e 's/^[ \t]*//' | awk 'NF' | sed 's/ *| */,/g' > ${OUTPUT_DIR}/dblist
  head -c -1 ${OUTPUT_DIR}/dblist > ${OUTPUT_DIR}/dblist2
  cat ${OUTPUT_DIR}/dblist2 | jq -R -n '[inputs | split(",") | {name: .[0], size: .[1] | tonumber, tables: []}] | {db: .}' > ${OUTPUT_DIR}/dblist.json

  for DB_NAME in $(jq '.db[] | .name' ${OUTPUT_DIR}/dblist.json | tr -d '"\r'); do
    echo "-> Get Tables for '${DB_NAME}'"

    psql -U ${POSTGRE_USERNAME} -h 127.0.0.1 -d ${DB_NAME} \
    -c "WITH tbl AS(SELECT table_schema,TABLE_NAME 
                    FROM information_schema.tables 
                    WHERE TABLE_NAME not like 'pg_%' 
                          AND table_schema in ('public')) 
                SELECT TABLE_NAME,(xpath('/row/c/text()', query_to_xml(format('SELECT count(*) as c  
                                                                              FROM %I.%I', table_schema, TABLE_NAME), FALSE, TRUE, '')))[1]::text::int AS rows_n  
                FROM tbl ORDER BY rows_n DESC;" | sed '1,2d' | sed '/(*. rows)/d' | sed -e 's/^[ \t]*//' | awk 'NF' | sed 's/ *| */,/g' > ${OUTPUT_DIR}/tablelist
    head -c -1 ${OUTPUT_DIR}/tablelist > ${OUTPUT_DIR}/tablelist2
    output_data=$(cat ${OUTPUT_DIR}/tablelist2 | sed '/(*. row)/d' | jq -nR '[inputs | split(",") | {name: .[0], row_count: .[1] | tonumber}]')

    jq --argjson inputs "$output_data" '(.db[] | select(.name == "'${DB_NAME}'").tables) += [$inputs]' ${OUTPUT_DIR}/dblist.json > ${OUTPUT_DIR}/output_data_tmp.json && cat ${OUTPUT_DIR}/output_data_tmp.json > ${OUTPUT_DIR}/dblist.json
  done

}

function check_mongo() {
  # port forward mongo
  if [ "${MONGO_MODE}" = "selfhosted" ]; then
    kubectl port-forward -n mongodb svc/mongodb ${MONGO_PORT}:${MONGO_PORT} &
  elif [ "${MONGO_MODE}" = "docdb" ]; then
    cp $(pwd)/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/db-validation/generic-forwarder.yaml mongo-forwarder.yaml

    sed -i "s#<remote_addr>#${DOCDB_ADDR}#g" mongo-forwarder.yaml
    sed -i "s#<pod_name>#${MONGO_FORWARDER}#g" mongo-forwarder.yaml
    sed -i "s#<remote_port>#${MONGO_PORT}#g" mongo-forwarder.yaml

    kubectl apply -f mongo-forwarder.yaml
    
    while [[ $(kubectl get pod -n default ${MONGO_FORWARDER} -o 'jsonpath={..status.conditions[?(@.type=="Ready")].status}') != "True" ]]; do 
      echo "waiting for pod" && sleep 1; 
    done
    kubectl port-forward -n default ${MONGO_FORWARDER} ${MONGO_PORT}:80 &
  fi

  sleep 15

  mongo -u ${MONGO_USERNAME} -p ${MONGO_PASSWORD} ${LOCALHOST}:${MONGO_PORT} --eval "db.getMongo().getDBNames()" --quiet > ${SERVICES_JSON}
  sed -i 's/'\''/"/g' ${SERVICES_JSON}

  i=0
  # list out database name
  for DB_NAME in $(jq '.[]' ${SERVICES_JSON} | tr -d '"\r'); do
    echo "-> Get collection for '${DB_NAME}'"

    # OPTION 1 : Get collection for each DB -> count data of each collection
    # # add current db_name to result_json
    # DB_VAL="{name: \"${DB_NAME}\", collection: []}"
    # jq ".db += [${DB_VAL}]" ${RESULT_JSON} > ${TMP_FILE} && cat ${TMP_FILE} > ${RESULT_JSON}

    # # list out collections for each DB
    # mongosh -u ${MONGO_USERNAME} -p ${MONGO_PASSWORD} ${LOCALHOST}:${MONGO_PORT}/${DB_NAME} \
    #   --eval "show collections" --quiet > ${COLLECTION_TXT}
    # while read COLLECTION_NAME; do
    #   echo "${COLLECTION_NAME}"
    #   # # count data for each collection
    #   COUNT_DATA=$(mongosh -u ${MONGO_USERNAME} -p ${MONGO_PASSWORD} ${LOCALHOST}:${MONGO_PORT}/${DB_NAME} \
    #     --eval "db.getCollection('${COLLECTION_NAME}').countDocuments()" --quiet)
    #   # countData=1945
    #   COL_VAL="{name: \"${COLLECTION_NAME}\", count: ${COUNT_DATA}}"
    #   jq ".db[${i}].collection += [${COL_VAL}]" ${RESULT_JSON} > ${TMP_FILE} && cat ${TMP_FILE} > ${RESULT_JSON}
    # done < ${COLLECTION_TXT}
    # end of OPTION 1

    # OPTION 2 : Get db.stats() for each DB
    mongo mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@${LOCALHOST}:${MONGO_PORT}/${DB_NAME} --eval "db.stats()" --quiet > ${TMP_FILE}
    DB_COLLECTIONS=$(grep 'collections' ${TMP_FILE} | awk -F ':' '{print $NF}' | tr -d ' ,')
    DB_OBJECTS=$(grep 'objects' ${TMP_FILE} | awk -F ':' '{print $NF}' | tr -d ' ,')
    DB_STORAGE_SIZE=$(grep 'storageSize' ${TMP_FILE} | awk -F ':' '{print $NF}' | tr -d ' ,')
    DB_INDEXES=$(grep 'indexes' ${TMP_FILE} | awk -F ':' '{print $NF}' | tr -d ' ,')
    DB_INDEX_SIZE=$(grep 'indexSize' ${TMP_FILE} | awk -F ':' '{print $NF}' | tr -d ' ,')
    DB_FILE_SIZE=$(grep 'fileSize' ${TMP_FILE} | awk -F ':' '{print $NF}' | tr -d ' ,')

    if [ ! -z "${DB_FILE_SIZE}" ]; then
      DB_VAL="{name: \"${DB_NAME}\", stats: {
            collections: ${DB_COLLECTIONS},
            objects: ${DB_OBJECTS},
            storageSize: ${DB_STORAGE_SIZE},
            indexes: ${DB_INDEXES},
            indexSize: ${DB_INDEX_SIZE},
            fileSize: ${DB_FILE_SIZE}
        }}"
    else
      DB_VAL="{name: \"${DB_NAME}\", stats: {
            collections: ${DB_COLLECTIONS},
            objects: ${DB_OBJECTS},
            storageSize: ${DB_STORAGE_SIZE},
            indexes: ${DB_INDEXES},
            indexSize: ${DB_INDEX_SIZE},
            fileSize: \"\"
        }}"
    fi  
   
    echo ${DB_VAL}

    jq ".db += [${DB_VAL}]" ${RESULT_JSON} > ${TMP_FILE} && cat ${TMP_FILE} > ${RESULT_JSON}

    i=$(( i+1 ))
    # end of OPTION 2
  done

  if [ "${MONGO_MODE}" = "docdb" ]; then
    kubectl delete -f mongo-forwarder.yaml
    rm mongo-forwarder.yaml
  fi

}

main "$@"