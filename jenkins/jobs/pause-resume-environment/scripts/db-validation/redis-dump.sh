#!/usr/bin/env bash
# Copyright (c) 2024 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

waitJob() {
    JOB_NAME="$1"
    NAMESPACE="justice"
    TIMEOUT="1800"  # in seconds
    INTERVAL=5

    echo "Waiting for Job '$JOB_NAME' in namespace '$NAMESPACE' to complete (timeout: ${TIMEOUT}s)..."

    start_time=$(date +%s)

    while true; do
        status=$(kubectl get job "$JOB_NAME" -n "$NAMESPACE" -o json 2>/dev/null)

        if [[ -z "$status" ]]; then
            echo "Job '$JOB_NAME' not found in namespace '$NAMESPACE'."
            exit 1
        fi

        succeeded=$(echo "$status" | jq '.status.succeeded // 0')
        failed=$(echo "$status" | jq '.status.failed // 0')

        if [[ "$succeeded" -ge 1 ]]; then
            echo "Pod logs:"
            # Exit true since the pod killed too fast sometimes
            kubectl logs -n $NAMESPACE $(kubectl get pods -n $NAMESPACE --selector=job-name=$JOB_NAME --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}') || true
            break
        fi

        if [[ "$failed" -ge 1 ]]; then
            echo "Pod logs:"
            # Exit true since the pod killed too fast sometimes
            kubectl logs -n $NAMESPACE $(kubectl get pods -n $NAMESPACE --selector=job-name=$JOB_NAME --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}') || true
        fi

        now=$(date +%s)
        elapsed=$((now - start_time))
        if [[ "$elapsed" -ge "$TIMEOUT" ]]; then
            echo "Timeout reached after ${TIMEOUT}s waiting for Job '$JOB_NAME'."
            exit 3
        fi

        echo "⏳ Still waiting... (${elapsed}s elapsed)"
        sleep "$INTERVAL"
    done
}

initRedisInstance(){
    # REDIS_PORT="6379"
    if [ -d "${IAC_ELASTICACHE}/${1}" ]; then
        echo "${IAC_ELASTICACHE}/${1} exist..."
        REDIS_ADDR=$(
            cd ${IAC_ELASTICACHE}/$1
            terragrunt output | grep endpoint | awk '{print $3}' | tr -d '"'
        )
        if [[ -z "$REDIS_ADDR" ]]; then
            echo "The instance from $1 folder seems not applied, skipping.."
            break
        fi
    else
        exit 1
    fi

    cd ${WORKDIR}
}

backupRestore(){
    cp ${WORKDIR}/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/db-validation/redis-dump-agent.yaml redis-dump-agent-$i.yaml
    sed -i "s#<redis_operation>#$1#g" redis-dump-agent-$2.yaml
    sed -i "s#<job_name>#pause-resume-redis-dump-$2-$1#g" redis-dump-agent-$2.yaml
    sed -i "s#<customer_name>#${CUSTOMER_NAME}#g" redis-dump-agent-$2.yaml
    sed -i "s#<project_name>#${PROJECT_NAME}#g" redis-dump-agent-$2.yaml
    sed -i "s#<environment_name>#${ENVIRONMENT_NAME}#g" redis-dump-agent-$2.yaml
    sed -i "s#<redis_instance_prefix>#${i}#g" redis-dump-agent-$2.yaml
    sed -i "s#<redis_addr>#${REDIS_ADDR}#g" redis-dump-agent-$2.yaml
    sed -i "s#<current_timestamp>#${CURRENT_TIMESTAMP}#g" redis-dump-agent-$2.yaml

    cat redis-dump-agent-$2.yaml

    # Pre cleanup
    kubectl delete -f redis-dump-agent-$2.yaml || true

    kubectl apply -f redis-dump-agent-$2.yaml
    
    # Wait job's pod to run
    sleep 10

    # Check if the job finished
    waitJob pause-resume-redis-dump-$2-$1

    # Post cleanup
    kubectl delete -f redis-dump-agent-$2.yaml || true
}

source .env
# kill $(lsof | grep kubectl | awk '{print $1}')
WORKDIR=$(pwd)
IAC_ELASTICACHE="${WORKDIR}/iac/live/${AWS_ACCOUNT_ID}/${CUSTOMER_NAME}/${PROJECT_NAME}/${AWS_REGION}/${ENVIRONMENT_NAME}/elasticache/"
# make sure the backup script updated
kubectl delete configmap -n justice justice-pause-resume-redis-dump
kubectl create configmap -n justice justice-pause-resume-redis-dump --from-file=${WORKDIR}/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/db-validation/redis-dump-script.sh
cp ${WORKDIR}/internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/db-validation/redis-dump-agent-sa.yaml redis-dump-agent-sa.yaml

sed -i "s#<customer_name>#${CUSTOMER_NAME}#g" redis-dump-agent-sa.yaml
sed -i "s#<project_name>#${PROJECT_NAME}#g" redis-dump-agent-sa.yaml
sed -i "s#<environment_name>#${ENVIRONMENT_NAME}#g" redis-dump-agent-sa.yaml
sed -i "s#<aws_account_id>#${AWS_ACCOUNT_ID}#g" redis-dump-agent-sa.yaml

kubectl delete -f redis-dump-agent-sa.yaml || true
kubectl apply -f redis-dump-agent-sa.yaml

while getopts o: flag
do
    case "${flag}" in
        o) operation=${OPTARG}
    esac
done

case $operation in
    backup)
        # ls -l ${IAC_ELASTICACHE} | awk '{print $9}' | grep -v '.md'
        for i in $(ls -l ${IAC_ELASTICACHE} | awk '{print $9}' | grep -v '.md'); do
            # echo $i
            initRedisInstance $i
            backupRestore backup $i
        done
    ;;
    restore)
        for i in $(ls -l ${IAC_ELASTICACHE} | awk '{print $9}' | grep -v '.md'); do
            initRedisInstance $i
            backupRestore restore $i
        done
esac
