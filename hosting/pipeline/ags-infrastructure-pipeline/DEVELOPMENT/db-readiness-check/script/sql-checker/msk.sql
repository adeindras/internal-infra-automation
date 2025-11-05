select 
    msk.arn,
    msk.cluster_name as resource_name,
    msk.cluster_type,
    msk.region,
    msk.provisioned,
    msk.provisioned -> 'ClientAuthentication' -> 'Sasl' -> 'Scram' -> 'Enabled' as sasl_scram_enabled,
    msk.provisioned -> 'CurrentBrokerSoftwareInfo' -> 'KafkaVersion' as kafka_version,
    msk.provisioned -> 'BrokerNodeGroupInfo' -> 'InstanceType' as instance_type,
    msk.provisioned -> 'NumberOfBrokerNodes' as num_of_brokers,
    autoscaling.policy_name as autoscaling_policy_name,
    msk.cluster_configuration, 
    msk.tags as tags,
    msk.tags ->> 'project' as project_tag
from 
    aws_msk_cluster as msk
join 
    aws_appautoscaling_policy as autoscaling
on
    msk.arn = autoscaling.resource_id
where
    autoscaling.service_namespace = 'kafka'

