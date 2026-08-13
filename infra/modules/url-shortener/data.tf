resource "aws_db_subnet_group" "postgres" {
  name       = "${local.name_prefix}-postgres"
  subnet_ids = [for subnet in aws_subnet.data : subnet.id]
  tags       = local.common_tags
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name_prefix}-postgres17"
  family = "postgres17"
  tags   = local.common_tags

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${local.name_prefix}-rds-monitoring"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "postgres" {
  identifier = local.name_prefix

  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class
  db_name        = "url_shortener"
  username       = "url_shortener"
  port           = 5432

  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.platform.arn

  allocated_storage     = 20
  max_allocated_storage = 200
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.platform.arn

  multi_az               = var.db_multi_az
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.postgres.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name

  backup_retention_period = var.db_backup_retention_days
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:05:00-sun:06:00"
  copy_tags_to_snapshot   = true

  auto_minor_version_upgrade      = true
  deletion_protection             = var.db_deletion_protection
  skip_final_snapshot             = var.db_skip_final_snapshot
  final_snapshot_identifier       = var.db_skip_final_snapshot ? null : "${local.name_prefix}-final"
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.platform.arn
  monitoring_interval             = 60
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  apply_immediately               = false

  tags = local.common_tags
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = "${local.name_prefix}-redis"
  subnet_ids = [for subnet in aws_subnet.data : subnet.id]
  tags       = local.common_tags
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = substr(replace(local.name_prefix, "_", "-"), 0, 40)
  description          = "TLS Redis cache for ${local.name_prefix}"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.cache_node_type
  port           = 6379

  num_cache_clusters         = var.cache_node_count
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.redis.name
  security_group_ids = [aws_security_group.cache.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  kms_key_id                 = aws_kms_key.platform.arn
  auth_token                 = random_password.redis.result
  auth_token_update_strategy = "SET"

  snapshot_retention_limit   = var.cache_snapshot_retention_days
  snapshot_window            = "04:00-05:00"
  maintenance_window         = "sun:06:00-sun:07:00"
  auto_minor_version_upgrade = true
  apply_immediately          = false

  tags = local.common_tags
}

resource "aws_backup_vault" "platform" {
  name        = local.name_prefix
  kms_key_arn = aws_kms_key.platform.arn
  tags        = local.common_tags
}

resource "aws_backup_plan" "platform" {
  name = "${local.name_prefix}-daily"

  rule {
    rule_name         = "daily"
    target_vault_name = aws_backup_vault.platform.name
    schedule          = "cron(0 5 ? * * *)"

    lifecycle {
      delete_after = var.db_backup_retention_days
    }

    recovery_point_tags = local.common_tags
  }

  tags = local.common_tags
}

resource "aws_iam_role" "backup" {
  name = "${local.name_prefix}-backup"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "backup.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_backup_selection" "platform" {
  name         = local.name_prefix
  plan_id      = aws_backup_plan.platform.id
  iam_role_arn = aws_iam_role.backup.arn
  resources = [
    aws_db_instance.postgres.arn,
    aws_elasticache_replication_group.redis.arn
  ]
}
