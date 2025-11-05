#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
# set -e

readonly ORIG_CWD="$PWD"
readonly OUTPUT_DIR=${ORIG_CWD}/output-db-check
readonly INPUT_DIR=${ORIG_CWD}/input-db-data-validation

# Get postgres result file
pg_file1="${OUTPUT_DIR}/dblist.json"
pg_file2="${INPUT_DIR}/dblist.json"

# Get mongo result file
mg_file1="${OUTPUT_DIR}/result.json"
mg_file2="${INPUT_DIR}/result.json"


if [[ ! -f "$pg_file1" || ! -f "$pg_file2" || ! -f "$mg_file1" || ! -f "$mg_file2" ]]; then
    echo "Error: One or any files do not exist."
    exit 1
fi

# Use the diff command to compare files
diff "$pg_file1" "$pg_file2"
# Check the exit status of diff to determine if there are differences
if [[ $? -eq 0 ]]; then
    echo "The postgres files are identical."
else
    echo "The postgres files have differences."
fi

diff "$mg_file1" "$mg_file2"
if [[ $? -eq 0 ]]; then
    echo "The mongo files are identical."
else
    echo "The mongo files have differences."
fi