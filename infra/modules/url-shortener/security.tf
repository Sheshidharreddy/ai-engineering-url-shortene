resource "aws_kms_key" "platform" {
  description             = "${local.name_prefix} data and secret encryption"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = local.common_tags
}

resource "aws_kms_alias" "platform" {
  name          = "alias/${local.name_prefix}"
  target_key_id = aws_kms_key.platform.key_id
}

resource "random_password" "redis" {
  length           = 48
  special          = true
  override_special = "!&#$^<>-"
}

resource "random_password" "management_api" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "application" {
  name                    = "${local.name_prefix}/application"
  description             = "Redis and management API credentials for ${local.name_prefix}"
  kms_key_id              = aws_kms_key.platform.arn
  recovery_window_in_days = var.environment == "prod" ? 30 : 7
  tags                    = local.common_tags
}

resource "aws_secretsmanager_secret_version" "application" {
  secret_id = aws_secretsmanager_secret.application.id
  secret_string = jsonencode({
    redis_password     = random_password.redis.result
    management_api_key = random_password.management_api.result
  })
}

resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb"
  description = "Public HTTPS ingress to the application load balancer"
  vpc_id      = aws_vpc.main.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-alb" })
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP redirect to HTTPS"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "Public HTTPS"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app"
  description = "Fargate application tasks"
  vpc_id      = aws_vpc.main.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-app" })
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "Application traffic from ALB"
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward requests to Fargate tasks"
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_vpc_security_group_egress_rule" "app_outbound" {
  security_group_id = aws_security_group.app.id
  description       = "TLS and dependency access through private routing"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "database" {
  name        = "${local.name_prefix}-database"
  description = "PostgreSQL access from Fargate tasks"
  vpc_id      = aws_vpc.main.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-database" })
}

resource "aws_vpc_security_group_ingress_rule" "database_from_app" {
  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL from application tasks"
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_security_group" "cache" {
  name        = "${local.name_prefix}-cache"
  description = "Redis access from Fargate tasks"
  vpc_id      = aws_vpc.main.id
  tags        = merge(local.common_tags, { Name = "${local.name_prefix}-cache" })
}

resource "aws_vpc_security_group_ingress_rule" "cache_from_app" {
  security_group_id            = aws_security_group.cache.id
  description                  = "Redis TLS from application tasks"
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.app.id
}
