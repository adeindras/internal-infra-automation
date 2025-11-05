select
  account_id,
  -- identity,
  -- kubernetes_network_config,
  logging,
  --l ->> 'Enabled' as logging_enabled,
  --l -> 'Types' as logging_type,
  --jsonb_path_query(logging, '$.ClusterLogging[*].Types') as test_type,
  --jsonb_path_query(logging, '$.ClusterLogging[*] ? (@.Enabled == "true")."Types[*]"') as test_type,
  --jsonb_path_query(logging, '$.ClusterLogging[*] ? (@.Enabled == true).Types') as logging_types_enabled,
  name as resource_name,
  region,
  -- resources_vpc_config,
  tags,
  version
from
    aws_eks_cluster,
    jsonb_array_elements(logging -> 'ClusterLogging') as l
-- where
--   l ->> 'Enabled' = 'true';