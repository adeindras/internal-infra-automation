#!/usr/bin/env bash

if [[ ${OSTYPE} = "msys" ]]; then
    for pid in $(netstat -ano | findstr :27017 | grep 'LISTENING' | awk -F 'LISTENING' '{print $NF}' | tr -d ' '); do
        echo "Killing PID : ${pid}"
        tskill ${pid}
        echo "PID ${pid} has been killed"
    done
    for pid in $(netstat -ano | findstr :4566 | grep 'LISTENING' | awk -F 'LISTENING' '{print $NF}' | tr -d ' '); do
        echo "Killing PID : ${pid}"
        tskill ${pid}
        echo "PID ${pid} has been killed"
    done
    for pid in $(netstat -ano | findstr :6379 | grep 'LISTENING' | awk -F 'LISTENING' '{print $NF}' | tr -d ' '); do
        echo "Killing PID : ${pid}"
        tskill ${pid}
        echo "PID ${pid} has been killed"
    done
else
    for pid in $(ps -ef | grep '27017' | awk -F ' ' '{print $2}'); do
        echo "Killing PID : ${pid}"
        kill -9 ${pid}
        echo "PID ${pid} has been killed"
    done
    for pid in $(ps -ef | grep '4566' | awk -F ' ' '{print $2}'); do
        echo "Killing PID : ${pid}"
        kill -9 ${pid}
        echo "PID ${pid} has been killed"
    done
    for pid in $(ps -ef | grep '6379' | awk -F ' ' '{print $2}'); do
        echo "Killing PID : ${pid}"
        kill -9 ${pid}
        echo "PID ${pid} has been killed"
    done
fi
