select 
    arn,
    cluster_name as resource_name,
    cluster_type,
    region,
    provisioned,
    provisioned -> 'ClientAuthentication' -> 'Sasl' -> 'Scram' -> 'Enabled' as sasl_scram_enabled,
    provisioned -> 'CurrentBrokerSoftwareInfo' -> 'KafkaVersion' as kafka_version,
    provisioned -> 'BrokerNodeGroupInfo' -> 'InstanceType' as instance_type,
    provisioned -> 'NumberOfBrokerNodes' as num_of_brokers,
    cluster_configuration, 
    tags as tags,
    tags ->> 'project' as project_tag
from 
    aws_msk_cluster

