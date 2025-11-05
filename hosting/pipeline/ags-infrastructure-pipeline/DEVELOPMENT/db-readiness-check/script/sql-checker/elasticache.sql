select
  cache_cluster_id as resource_name,
  tags ->> 'Name' as cluster_name,
  cache_node_type,
  cache_cluster_status,
  engine_version as redis_version,
  snapshot_retention_limit as backup_retention_period,
  preferred_availability_zone,
  at_rest_encryption_enabled as encryption_at_rest,
  tags
from
  aws_elasticache_cluster
where
  preferred_availability_zone <> 'Multiple';