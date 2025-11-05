#!/bin/bash

curDir=$(pwd)
tmpDir=$(mktemp -d)
tmpFile=${curDir}/tfversion.list
rm -rf ${tmpFile} | true
cd ${tmpDir}
git clone --depth 1 git@bitbucket.org:accelbyte/iac.git
cd iac
find . -type f -name .terraform-version | while read line; do
  echo "$(cat ${line})" >> ${tmpFile}
done
rm -rf ${tmpDir}/iac