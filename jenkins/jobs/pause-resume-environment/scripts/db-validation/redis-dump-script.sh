#!/usr/bin/env bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

backupRedis(){
    redis-dump-go -host ${REDIS_ADDR} -port ${REDIS_PORT} > $1-redis-backup.txt

    aws_dir="s3://${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data"
    aws s3 cp $1-redis-backup.txt "$aws_dir/$1-redis-backup-last.txt"
    aws s3 cp $1-redis-backup.txt "$aws_dir/$1-redis-backup-${CURRENT_TIMESTAMP}.txt"
}

restoreRedis(){
    aws_dir="s3://${CUSTOMER_NAME}-${PROJECT_NAME}-${ENVIRONMENT_NAME}-justice-paused-environment-data"
    aws s3 cp "$aws_dir/$1-redis-backup-last.txt" $1-redis-backup.txt

    redis-cli -h $REDIS_ADDR -p $REDIS_PORT INFO replication
    redis-cli -h $REDIS_ADDR -p $REDIS_PORT INFO server

    redis-cli -h ${REDIS_ADDR} -p ${REDIS_PORT} --pipe < $1-redis-backup.txt
    redis-cli -h ${REDIS_ADDR} -p ${REDIS_PORT} DBSIZE
}

while getopts o: flag
do
    case "${flag}" in
        o) operation=${OPTARG}
    esac
done

case $operation in
    backup)
        backupRedis $3
    ;;
    restore)
        restoreRedis $3
esac
