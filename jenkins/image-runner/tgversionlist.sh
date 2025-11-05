#!/bin/bash

curDir=$(pwd)
tmpDir=$(mktemp -d)
tmpFile=${curDir}/tgversion.list
rm -rf ${tmpFile} | true
cd ${tmpDir}
git clone --depth 1 git@bitbucket.org:accelbyte/iac.git
cd iac
find . -type f -name .terragrunt-version | while read line; do 
  echo "$(cat ${line})" >> ${tmpFile}
done
rm -rf ${tmpDir}/iac