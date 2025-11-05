select 
    db_cluster_identifier as resource_name, 
    engine, 
    engine_version, 
    storage_encrypted,
    storage_type,
    preferred_backup_window,
    backup_retention_period,
    tags as tags,
    tags_src ->> 'project' as project_tag
from 
    aws_docdb_cluster

