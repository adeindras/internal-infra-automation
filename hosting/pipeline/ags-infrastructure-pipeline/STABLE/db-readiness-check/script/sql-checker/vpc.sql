with expanded_cidr_blocks as (
    select
        vpc.vpc_id,
        jsonb_array_elements(vpc.cidr_block_association_set)->>'CidrBlock' as cidr_block
    from
        aws_vpc as vpc
)
select
    vpc.account_id as account_id,
    vpc.title as resource_name,
    vpc.is_default as is_default_vpc,
    vpc.cidr_block as primary_cidr_block,
    (select jsonb_agg(cidr_block) from expanded_cidr_blocks where expanded_cidr_blocks.vpc_id = vpc.vpc_id) as available_cidr_blocks,
    vpc.state,
    vpc.region
from
    aws_vpc as vpc 
where
    vpc.title = '${CLUSTER_NAME}';
