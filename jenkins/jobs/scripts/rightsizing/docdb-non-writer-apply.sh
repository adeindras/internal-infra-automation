#!/bin/bash
resources=$(mktemp)
instances=$(mktemp)

set +x
# get the cluster identifier
terragrunt show -json $@ | jq '[.values.root_module.child_modules[].resources[]]' > ${resources}
clusterId=$(cat ${resources} | jq -r '.[] | select(.type == "aws_docdb_cluster") | .values.id')

writerInstance=$(aws docdb describe-db-clusters --db-cluster-identifier ${clusterId} --output text --no-cli-pager --query 'DBClusters[0].DBClusterMembers[?IsClusterWriter==`true`].DBInstanceIdentifier | [0]')

cat ${resources} | jq '[.[] | select(.type == "aws_docdb_cluster_instance")]' > ${instances}

echo "the writer instance is ${writerInstance}"
count=$(cat ${instances} | jq 'length')

for i in $(seq 0 $((${count}-1))); do
  echo ":: checking instance ${i}"
  instanceId=$(cat ${instances} | INDEX=${i} jq -r '.[env.INDEX|tonumber].values.id')
  if [[ "${instanceId}" != "${writerInstance}" ]]; then
    echo "got non-writer instance ${instanceId}"
    target=$(cat ${instances} | INDEX=${i} jq -r '.[env.INDEX|tonumber].address')
    terragrunt apply --auto-approve -target="${target}" "$@"

    # failover mechanism
    echo "Failover to instance ${instanceId}"  
    aws docdb failover-db-cluster --db-cluster-identifier ${clusterId} --target-db-instance-identifier ${instanceId}
    
    # ---------------------------
    # wait for instance failover
    # ---------------------------
    start_time=$(date +%s)
    while true; do
      writerInstance=$(aws docdb describe-db-clusters --db-cluster-identifier ${clusterId} --output text --no-cli-pager --query 'DBClusters[0].DBClusterMembers[?IsClusterWriter==`true`].DBInstanceIdentifier | [0]')

      if [[ "${writerInstance}" == "${instanceId}" ]]; then
        echo "Failover done -- Writer instance is ${writerInstance}"
        # break while loop
        break
      fi

      echo "Make sure failover done -- Iteration at $(date)"
      sleep 5
      current_time=$(date +%s)
      elapsed=$((current_time - start_time))

      if [[ "$elapsed" -ge 120 ]]; then
        echo "Reached 120 seconds. Exiting loop."
        # break while loop
        break
      fi
    done
    # ---------------------------
    # ---------------------------

    # break for loop
    break 
  fi
done