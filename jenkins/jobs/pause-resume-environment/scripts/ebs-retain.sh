#!/usr/bin/env bash
# Copyright (c) 2023 AccelByte Inc. All Rights Reserved.
# This is licensed software from AccelByte Inc, for limitations
# and restrictions contact your company contract manager.

gather_volume_data() {
    local pvc_usage=$1
    local pvc_namespace=$2
    local pvc_name=$3

    echo "Generating volume data for $pvc_name pvc in $pvc_namespace namespace.."
    pvc=$(kubectl get pvc -n $pvc_namespace $pvc_name -ojson)
    pv_name=$(echo $pvc | jq .spec.volumeName | tr -d '"')

    echo "Checking pv $pv_name"
    pv_name_cut=$(echo $pv_name | cut -c1-40)
    pv=$(kubectl get pv $pv_name -ojson)
    sc_name=$(echo $pv | jq .spec.storageClassName | tr -d '"')

    if [ $sc_name = "gp2" ]; then
        volume_id=$(echo $pv | jq .spec.awsElasticBlockStore.volumeID | tr -d '"')
        az_name=$(echo $pv | jq '.metadata.labels."topology.kubernetes.io/zone"' | tr -d '"')
    else
        volume_id=$(echo $pv | jq .spec.csi.volumeHandle | tr -d '"')
        az_name=$(aws ec2 describe-volumes --volume-ids $volume_id | jq ".Volumes[0].AvailabilityZone" | tr -d '"')
    fi
    pv_size=$(echo $pv | jq .spec.capacity.storage | tr -d '"')

    if [ $az_name = "null" ]; then
        region=${AWS_REGION}
    else
        region=$(echo ${az_name::-1})
    fi
    
    timestamp=$(date +%s)

    echo "Creating EBS Volume snapshot for volume id $volume_id..."
    snapshot_id=$(aws ec2 create-snapshot --volume-id $volume_id --region $region --tag-specifications "ResourceType=snapshot,Tags=[{Key=Name,Value=$pvc_name-$timestamp},{Key=customer_name,Value=${CUSTOMER_NAME}},{Key=project,Value=${PROJECT_NAME}},{Key=environment,Value=${ENVIRONMENT_NAME}}]" | jq .SnapshotId | tr -d '"')

    echo "Gathered information:"
    echo "---------------------------------------------"
    echo "PVC namespcae         : $pvc_namespace"
    echo "PVC name              : $pvc_name"
    echo "PV name               : $pv_name_cut"
    echo "EBS Volume ID         : $volume_id"
    echo "Volume capacity       : $pv_size"
    echo "Storage class         : $sc_name"
    echo "Volume Region         : $region"
    echo "Volume AZ             : $az_name"
    echo "Volume snapshot ID    : $snapshot_id"
    echo "---------------------------------------------"
    echo ""

    echo "Writting json output..."
    if [ ! -f db-saved.json ]; then
        echo "File not found!"
        jq -n --arg pvcNamespace "$pvc_namespace" \
           --arg pvcName "$pvc_name" \
           --arg pvName "$pv_name_cut" \
           --arg volumeId "$volume_id" \
           --arg pvSize "$pv_size" \
           --arg scName "$sc_name" \
           --arg pvRegion "$region" \
           --arg pvAz "$az_name" \
           --arg snapshotID "$snapshot_id" \
           '{'$pvc_usage': $ARGS.named}'> db-saved.json
    else
        jq --arg pvcNamespace "$pvc_namespace" \
           --arg pvcName "$pvc_name" \
           --arg pvName "$pv_name_cut" \
           --arg volumeId "$volume_id" \
           --arg pvSize "$pv_size" \
           --arg scName "$sc_name" \
           --arg pvRegion "$region" \
           --arg pvAz "$az_name" \
           --arg snapshotID "$snapshot_id" \
           '. + {'$pvc_usage': $ARGS.named}' db-saved.json > db-saved2.json
           rm db-saved.json
           mv db-saved2.json db-saved.json
    fi
}

resume_volume() {
    echo "Resuming self-hosted $1 databse..."
    local pvc_namespace=$(cat db-saved-ssm.json | jq .$1.pvcNamespace | tr -d '"')
    local pvc_name=$(cat db-saved-ssm.json | jq .$1.pvcName | tr -d '"')
    local pv_name=$(cat db-saved-ssm.json | jq .$1.pvName | tr -d '"')
    local volume_id=$(cat db-saved-ssm.json | jq .$1.volumeId | tr -d '"')
    local pv_size=$(cat db-saved-ssm.json | jq .$1.pvSize | tr -d '"')
    local sc_name=$(cat db-saved-ssm.json | jq .$1.scName | tr -d '"')
    local region=$(cat db-saved-ssm.json | jq .$1.pvRegion | tr -d '"')
    local az_name=$(cat db-saved-ssm.json | jq .$1.pvAz | tr -d '"')
    local snapshot_id=$(cat db-saved-ssm.json | jq .$1.snapshotID | tr -d '"')

    echo "---------------------------------------------"
    echo "PVC namespcae         : $pvc_namespace"
    echo "PVC name              : $pvc_name"
    echo "PV name               : $pv_name"
    echo "EBS Volume ID         : $volume_id"
    echo "Volume capacity       : $pv_size"
    echo "Storage class         : $sc_name"
    echo "Volume Region         : $region"
    echo "Volume AZ             : $az_name"
    echo "Volume snapshot ID    : $snapshot_id"
    echo "---------------------------------------------"
    echo ""
    
    echo "Checking EBS volume..."
    if [ $(aws ec2 describe-volumes --volume-ids $volume_id | jq ".Volumes[0].VolumeId") ]; then
        echo "EBS volume exist, continuing..."
    else
        if [ $(aws ec2 describe-snapshots --region us-east-2 --snapshot-ids $snapshot_id | jq ".Snapshots[0].SnapshotId") ]; then
            echo -e "${RED}Couldn't find the EBS volume, but snapshot found, please check the snapshot and restore it manually!!${NC}"
            echo -e "then update the volume ID in this ssm parameter store ${GREEN}/eks/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/self_hosted_database_volume${NC}"
            echo "and rerun this job!"
        else
            echo -e "${RED}Couldn't find the EBS volume $volume_id and its snapshot $snapshot_id, please resolve this manually!!${NC}"
        fi
        echo -e "${RED}Exiting...${NC}"
        exit 1
    fi 

    echo "Generating PV and PVC templates..."
    if [ ! -f internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/pvc-template/$1-pvc.yaml ]; then
        echo "Specified database type manifest template not found!"
        exit 1
    else
        cp internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/pvc-template/$1-pvc.yaml $1-pvc.yaml
    fi

    if [ ! -f internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/pvc-template/pv-$sc_name.yaml ]; then
        echo "Specified storageclass type manifest template not found!"
        exit 1
    else
        cp internal-infra-automation/jenkins/jobs/pause-resume-environment/scripts/pvc-template/pv-$sc_name.yaml $1-pv-$sc_name.yaml
    fi
    
    # Generate PV manifest
    sed -i "s#<pv_name>#$pv_name#g" $1-pv-$sc_name.yaml
    sed -i "s#<pv_size>#$pv_size#g" $1-pv-$sc_name.yaml
    sed -i "s#<pvc_name>#$pvc_name#g" $1-pv-$sc_name.yaml
    sed -i "s#<pvc_namespace>#$pvc_namespace#g" $1-pv-$sc_name.yaml
    sed -i "s#<volume_id>#$volume_id#g" $1-pv-$sc_name.yaml
    sed -i "s#<az_name>#$az_name#g" $1-pv-$sc_name.yaml
    sed -i "s#<region>#$region#g" $1-pv-$sc_name.yaml
    sed -i "s#<sc_name>#$sc_name#g" $1-pv-$sc_name.yaml

    # Generate PVC manifest
    sed -i "s#<pv_name>#$pv_name#g" $1-pvc.yaml
    sed -i "s#<pvc_namespace>#$pvc_namespace#g" $1-pvc.yaml
    sed -i "s#<pvc_name>#$pvc_name#g" $1-pvc.yaml
    sed -i "s#<pv_size>#$pv_size#g" $1-pvc.yaml
    sed -i "s#<sc_name>#$sc_name#g" $1-pvc.yaml
    sed -i "s#<pv_name>#$pv_name#g" $1-pvc.yaml


    echo "Applying $1 resumed EBS volume manifest..."
    # Apply PV
    kubectl apply -f $1-pv-$sc_name.yaml

    # Apply PVC
    kubectl apply -f $1-pvc.yaml
    echo ""
    echo "Cleaning up.."
    rm $1-pvc.yaml
    rm $1-pv-$sc_name.yaml
}

store_ssm() {
    echo "Saving to parameter store..."
    aws ssm put-parameter \
    --name "/eks/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/self_hosted_database_volume" \
    --type String \
    --overwrite \
    --value file://db-saved.json > saved
    cat saved
    rm saved
}

get_volume_ssm() {
    aws ssm get-parameter \
    --name "/eks/${CUSTOMER_NAME}/${PROJECT_NAME}/${ENVIRONMENT_NAME}/self_hosted_database_volume" \
    --output text \
    --query Parameter.Value > db-saved-ssm.json
}


RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

while getopts o:n:d:v: flag
do
    case "${flag}" in
        o) operation=${OPTARG};;
        n) namespace=${OPTARG};;
        d) database=${OPTARG};;
        v) pvcname=${OPTARG}
    esac
done

case $operation in
    pause)
        source .env
        gather_volume_data $namespace $database $pvcname
    ;;
    resume)
        source .env
        get_volume_ssm
        resume_volume $database
    ;;
    uploadssm)
        source .env
        store_ssm
    ;;
esac