terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0, < 7.0"
    }
  }

  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region = var.aws_region
}

module "url_shortener" {
  source = "../../modules/url-shortener"

  environment        = "dev"
  aws_region         = var.aws_region
  base_domain        = var.base_domain
  subdomain          = "dev.go"
  ecr_repository_url = var.ecr_repository_url
  image_tag          = var.image_tag
  vpc_cidr           = "10.20.0.0/16"

  availability_zone_count          = 2
  single_nat_gateway               = true
  desired_count                    = 2
  minimum_count                    = 2
  maximum_count                    = 4
  db_pool_size                     = 5
  db_application_connection_budget = 24

  db_instance_class             = "db.t4g.small"
  db_multi_az                   = false
  db_backup_retention_days      = 7
  db_deletion_protection        = false
  db_skip_final_snapshot        = true
  cache_node_type               = "cache.t4g.small"
  cache_node_count              = 2
  cache_snapshot_retention_days = 3

  log_retention_days = 14
  alarm_email        = var.alarm_email

  tags = {
    Owner      = "sheshidhar"
    CostCenter = "url-shortener-dev"
  }
}

output "service_url" {
  value = module.url_shortener.service_url
}
