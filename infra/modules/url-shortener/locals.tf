data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_route53_zone" "public" {
  name         = "${var.base_domain}."
  private_zone = false
}

locals {
  name_prefix = "${var.project_name}-${var.environment}"
  fqdn        = "${var.subdomain}.${var.base_domain}"
  azs         = slice(data.aws_availability_zones.available.names, 0, var.availability_zone_count)

  public_subnet_cidrs = {
    for index, az in local.azs : az => cidrsubnet(var.vpc_cidr, 4, index)
  }
  app_subnet_cidrs = {
    for index, az in local.azs : az => cidrsubnet(var.vpc_cidr, 4, index + 4)
  }
  data_subnet_cidrs = {
    for index, az in local.azs : az => cidrsubnet(var.vpc_cidr, 4, index + 8)
  }
  nat_gateway_azs = var.single_nat_gateway ? toset([local.azs[0]]) : toset(local.azs)

  common_tags = merge(var.tags, {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "Terraform"
  })
}
