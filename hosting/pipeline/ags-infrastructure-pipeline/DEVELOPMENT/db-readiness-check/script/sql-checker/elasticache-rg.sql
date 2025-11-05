select
  replication_group_id as resource_name,
  description,
  cache_node_type,
  cluster_enabled,
  automatic_failover, 
  multi_az,
  jsonb_array_length(member_clusters) as member_clusters
from
  aws_elasticache_replication_group;
