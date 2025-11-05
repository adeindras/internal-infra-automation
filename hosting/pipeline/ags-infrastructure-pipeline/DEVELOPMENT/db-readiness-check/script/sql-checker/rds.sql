select 
    rds.db_instance_identifier as resource_name, 
    rds.engine, 
    rds.engine_version, 
    rds.class, 
    rds.max_allocated_storage, 
    rds.ca_certificate_identifier, 
    rds.storage_encrypted,
    rds.multi_az,
    rds.backup_retention_period, 
    rdsbackup.status as backup_status,
    rds.tags as tags,
    rds.tags ->> 'project' as project_tag
from 
    aws_rds_db_instance as rds 
join 
    aws_rds_db_instance_automated_backup as rdsbackup 
on 
    rds.db_instance_identifier = rdsbackup.db_instance_identifier;
