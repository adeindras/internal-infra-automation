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
  game                         = "shared"
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
  unique_id                             = dependency.unique_id.outputs.id
  game                                  = local.game
  attach_deny_insecure_transport_policy = true

  server_side_encryption_configuration = {
    rule = {
      apply_server_side_encryption_by_default = {
        sse_algorithm = "AES256"
      },
    }
  }

  trim_prefix = format("%s-", local.name)

  s3_bucket_list = {
    "eks-log" = {
      name = "${local.name}-eks-logs"
      attach_policy = true
      policy = <<EOT
{
    "Version": "2012-10-17",
    "Statement": [
      {
          "Action": "s3:GetBucketAcl",
          "Effect": "Allow",
          "Resource": "arn:aws:s3:::${local.name}-eks-logs",
          "Principal": { "Service": "logs.${local.aws_region}.amazonaws.com" },
          "Condition": {
            "StringEquals": {
                "aws:SourceAccount": [
                    "${local.aws_account_id}"
                ]
            },
            "ArnLike": {
              "aws:SourceArn": [
                  "arn:aws:logs:${local.aws_region}:${local.aws_account_id}:log-group:/aws/eks/${local.name}/cluster:*"
               ]
            }
          }
      },
      {
          "Action": "s3:PutObject" ,
          "Effect": "Allow",
          "Resource": "arn:aws:s3:::${local.name}-eks-logs/*",
          "Principal": { "Service": "logs.${local.aws_region}.amazonaws.com" },
          "Condition": {
            "StringEquals": {
                "s3:x-amz-acl": "bucket-owner-full-control",
                "aws:SourceAccount": [
                    "${local.aws_account_id}"
                ]
            },
            "ArnLike": {
              "aws:SourceArn": [
                  "arn:aws:logs:${local.aws_region}:${local.aws_account_id}:log-group:/aws/eks/${local.name}/cluster:*"
              ]
            }
          }
      }
    ]
}
EOT
    }
  }
}
