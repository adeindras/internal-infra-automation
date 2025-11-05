#!/bin/bash

set -euo pipefail

# COLOUR CONSTANTS
GREEN='\033[0;32m'
LIGHT_BLUE='\033[1;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

if [[ "$1" == "ratelimit" ]]; then
    source ratelimit-rollout.sh
    if [[ -z "$2" ]]; then
        preflight
        postflight
    elif [[ "$2" == "pre" ]]; then
        preflight
    elif [[ "$2" == "post" ]]; then
        postflight
    fi
fi

if [[ "$1" == "iam-v2-api-blocking" ]]; then
    source iam-v2-api-blocking.sh
    if [[ -z "$2" ]]; then
        preflight
        postflight
    elif [[ "$2" == "pre" ]]; then
        preflight
    elif [[ "$2" == "post" ]]; then
        postflight
    fi
fi
