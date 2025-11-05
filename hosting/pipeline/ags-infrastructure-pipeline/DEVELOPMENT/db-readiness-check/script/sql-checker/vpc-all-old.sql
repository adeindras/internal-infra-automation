select
    vpc.account_id as account_id,
    vpc.arn as arn,
    vpc.title as resource_name,
    vpc.cidr_block as vpc_cidr_block,
    -- jsonb_agg(vpc_cidr_blocks.cidr_block) as vpc_cidr_blocks,
    --jsonb_array_elements(vpc.cidr_block_association_set) as vpc_cidr_block_association_set,
    vpc.region,
    vpcsubnet.availability_zone as subnet_availability_zone,
    vpc.state,
    --vpc.tags,
    vpc.vpc_id,
    vpcsubnet.available_ip_address_count as vpc_subnet_available_ip_address_count,
    power(2, 32 - masklen(vpcsubnet.cidr_block :: cidr)) -1 as vpc_subnet_raw_size
from
    aws_vpc_subnet as vpcsubnet
join 
    aws_vpc as vpc 
on 
    vpcsubnet.vpc_id = vpc.vpc_id
--left join lateral (
--    select 
--        jsonb_array_elements(vpc.cidr_block_association_set)->>'CidrBlock' as cidr_block
--) as vpc_cidr_blocks
--on true
where
    --vpc.title = '$CLUSTER_NAME';
    vpc.title = 'theorycraft-justice-prod';
