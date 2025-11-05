locals {
  environment_vars = read_terragrunt_config(find_in_parent_folders("env.hcl"))
  account_vars     = read_terragrunt_config(find_in_parent_folders("account.hcl"))
  region_vars      = read_terragrunt_config(find_in_parent_folders("region.hcl"))
  customer_vars    = read_terragrunt_config(find_in_parent_folders("customer.hcl"))
  project_vars     = read_terragrunt_config(find_in_parent_folders("project.hcl"))

  aws_account_id        = local.account_vars.locals.aws_account_id
  main_domain_zone_id   = local.account_vars.locals.main_domain_zone_id
  main_domain_zone_name = local.account_vars.locals.main_domain_zone_name
  aws_region            = local.region_vars.locals.aws_region
  customer_name         = local.customer_vars.locals.customer_name
  project_name          = local.project_vars.locals.project_name
  environment_name      = local.environment_vars.locals.environment_name
  game                  = "gamesandbox"
  service               = "justice"
  service_group         = "justice-shared-srvrlss"

  name                  = format("rds-aurora-%s-%s-%s-%s", local.customer_name, local.project_name, local.environment_name, local.service_group)

  pgbadger_env_type        = "prod" # possible values: dev | prod | loadtest
  pgbadger_rds_config_path = "../../../../../../../../modules/cloudwatch_db_pgbadger/v1.0.0/rdsconfig/rds-parameters-config.hcl"
  pgbadger_rds_config      = fileexists(local.pgbadger_rds_config_path) ? read_terragrunt_config(local.pgbadger_rds_config_path).locals : {}

}

terraform {
  source = "../../../../../../../../../modules//rds_aurora/v9.13.0"
  extra_arguments "bucket" {
    commands = get_terraform_commands_that_need_vars()
    optional_var_files = [
      find_in_parent_folders("account.tfvars", "ignore"),
      find_in_parent_folders("region.tfvars", "ignore"),
      find_in_parent_folders("env.tfvars", "ignore"),
    ]
  }
}

include {
  path = find_in_parent_folders()
}

dependency "unique_id" {
  config_path = "../../../unique_id"
}

dependency "vpc" {
  config_path = "../../../vpc"
}

dependency "dns_zone" {
  config_path = "../../../dns_zone"
}

dependency "additional_subnets" {
  config_path = "../../../additional_subnets"
}

inputs = {
  customer_name                   = local.customer_name
  project                         = local.project_name
  environment                     = local.environment_name
  unique_id                       = dependency.unique_id.outputs.id
  game                            = local.game
  service                         = local.service
  vpc_id                          = dependency.vpc.outputs.vpc_id
  subnets                         = dependency.vpc.outputs.private_subnets_ids
  allowed_cidr_blocks             = dependency.vpc.outputs.private_subnets_cidr_blocks
  engine                          = "aurora-postgresql"
  engine_version                  = "16.4"

  instance_class                  = "db.serverless"
  #Writer: 1 Reader: 1
  instances = {
    one   = {
      identifier = local.name
    }

    two = {
      identifier = format("%s-two", local.name)
    }
  }

  serverlessv2_scaling_configuration = {
    min_capacity             = 2
    max_capacity             = 4
  }

  service_group                   = "justice-shared-srvrlss"
  storage_encrypted               = "true"
  
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  create_cloudwatch_log_group     = false
  cloudwatch_log_group_retention_in_days = 7
  
  performance_insights_enabled    = true
  performance_insights_retention_period = 7
  enable_slow_query_metric        = false

  storage_type                    = "aurora" #Aurora Standard
  apply_immediately               = true

  backup_retention_period         = 15
  auto_minor_version_upgrade      = false

  enable_cross_region_snapshot    = true

  create_db_cluster_parameter_group = "true"
  db_cluster_parameter_group_family  = "aurora-postgresql16"
  db_cluster_parameter_group_parameters = concat([
    {
      apply_method = "pending-reboot"
      name         = "rds.logical_replication"
      value        = "0" #Temporary set to 0. Once migration done, revert back to 1.
    },
    {
      apply_method = "pending-reboot"
      name         = "max_logical_replication_workers"
      value        = "20"
    },
    {
      apply_method = "pending-reboot"
      name         = "max_replication_slots"
      value        = "20"
    },
    {
      apply_method = "pending-reboot"
      name         = "max_worker_processes"
      value        = "20"
    },
    {
      apply_method = "pending-reboot"
      name         = "rds.force_ssl"
      value        = "0"
    },
    ],
    (length(local.pgbadger_rds_config) > 0) ? (local.pgbadger_env_type != "prod" ? local.pgbadger_rds_config.rds_parameters_for_pgbadger_dev : local.pgbadger_rds_config.rds_parameters_for_pgbadger_prod) : []

    ,
    # below space for overriding pgbadger_rds_config, if necessary
    []


  )

  # Enable internal domain in route53
  enable_route53_internal_domain = true
  route53_zone_id                = dependency.dns_zone.outputs.route53_internal_zone_id
  route53_name                   = "rds-aurora-justice-shared-srvrlss.${dependency.dns_zone.outputs.route53_internal_zone_name}"
}
