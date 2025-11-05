select
  name as resource_name,
  arn,
  state,
  created_by,
  event_bus_name
from
  aws_eventbridge_rule;