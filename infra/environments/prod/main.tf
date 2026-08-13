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

  environment        = "prod"
  aws_region         = var.aws_region
  base_domain        = var.base_domain
  subdomain          = "go"
  ecr_repository_url = var.ecr_repository_url
  image_tag          = var.image_tag
  vpc_cidr           = "10.40.0.0/16"

  availability_zone_count          = 3
  single_nat_gateway               = false
  desired_count                    = 3
  minimum_count                    = 3
  maximum_count                    = 12
  db_pool_size                     = 12
  db_application_connection_budget = 160

  task_cpu    = 1024
  task_memory = 2048

  db_instance_class             = "db.r7g.large"
  db_multi_az                   = true
  db_backup_retention_days      = 35
  db_deletion_protection        = true
  db_skip_final_snapshot        = false
  cache_node_type               = "cache.r7g.large"
  cache_node_count              = 3
  cache_snapshot_retention_days = 14

  log_retention_days      = 90
  alarm_email             = var.alarm_email
  alb_deletion_protection = true
  global_rate_limit       = 6000
  creation_rate_limit     = 200

  tags = {
    Owner      = "sheshidhar"
    CostCenter = "url-shortener-prod"
  }
}

output "service_url" {
  value = module.url_shortener.service_url
}
