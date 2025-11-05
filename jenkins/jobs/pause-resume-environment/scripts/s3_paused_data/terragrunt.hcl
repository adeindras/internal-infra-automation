locals {
  environment_vars = read_terragrunt_config(find_in_parent_folders("env.hcl"))
  account_vars     = read_terragrunt_config(find_in_parent_folders("account.hcl"))
  region_vars      = read_terragrunt_config(find_in_parent_folders("region.hcl"))
  customer_vars    = read_terragrunt_config(find_in_parent_folders("customer.hcl"))
  project_vars     = read_terragrunt_config(find_in_parent_folders("project.hcl"))

  aws_account_id               = local.account_vars.locals.aws_account_id
  main_domain_zone_id          = local.account_vars.locals.main_domain_zone_id
  main_domain_zone_name        = local.account_vars.locals.main_domain_zone_name
  aws_region                   = local.region_vars.locals.aws_region
  customer_name                = local.customer_vars.locals.customer_name
  project_name                 = local.project_vars.locals.project_name
  environment_name             = local.environment_vars.locals.environment_name
  environment_domain_zone_name = local.environment_vars.locals.environment_domain_zone_name
  game                         = "accelbyte"
  name                         = format("%s-%s-%s", local.customer_name, local.project_name, local.environment_name)
}

terraform {
  source = "../../../../../../../modules//s3-bucket/v2.14.1"
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
  config_path = "../unique_id"
}

inputs = {
  unique_id     = dependency.unique_id.outputs.id
  game          = local.game
  force_destroy = false

  server_side_encryption_configuration = {
    rule = {
      apply_server_side_encryption_by_default = {
        sse_algorithm = "AES256"
      },
    }
  }

  attach_deny_insecure_transport_policy = true

  #This variable is used to trim the value of s3_bucket_list and used trimmed string for the value of "service" tag
  #We want "service" tag to have a value like e.g justice-gdpr-service and not local.bucket-prefix-justice-gdpr-service
  trim_prefix = format("%s-", local.name)

  s3_bucket_list = {
    "justice-paused-environment-data" = {
      name = "${local.name}-justice-paused-environment-data"
    }
  }
}
