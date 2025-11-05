#!/usr/bin/env bash
# Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.
# set -e

# Generate list of the latest tg version used
curDir=$(pwd)
tmpDir=$(mktemp -d)
tmpFile=${curDir}/tgversion.list
cd iac
find . -type f -name .terragrunt-version | while read line; do 
  echo "$(cat ${line})" >> ${tmpFile}
done
cd ..

# Check and install missing versions
for i in $(cat ./tgversion.list); do
  tgenv install ${i}
done

tgenv install latest
tgenv use latest