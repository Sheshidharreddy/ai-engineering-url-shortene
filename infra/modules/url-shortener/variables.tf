variable "project_name" {
  description = "Stable project identifier used in AWS resource names."
  type        = string
  default     = "sheshidhar-url-shortener"
}

variable "environment" {
  description = "Deployment environment."
  type        = string

  validation {
    condition     = contains(["dev", "uat", "prod"], var.environment)
    error_message = "environment must be dev, uat, or prod."
  }
}

variable "aws_region" {
  description = "AWS region used by the platform."
  type        = string
}

variable "base_domain" {
  description = "Existing public Route53 hosted-zone name, without a trailing dot."
  type        = string
}

variable "subdomain" {
  description = "Record name relative to base_domain, for example go or dev.go."
  type        = string
}

variable "ecr_repository_url" {
  description = "Shared ECR repository URL created by infra/bootstrap."
  type        = string
}

variable "image_tag" {
  description = "Immutable application image tag, normally the Git commit SHA."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR for the environment VPC."
  type        = string
}

variable "availability_zone_count" {
  description = "Number of availability zones. Production should use three."
  type        = number
  default     = 3

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 3
    error_message = "availability_zone_count must be two or three."
  }
}

variable "single_nat_gateway" {
  description = "Use one NAT gateway to reduce non-production cost."
  type        = bool
  default     = false
}

variable "task_cpu" {
  description = "Fargate task CPU units."
  type        = number
  default     = 1024
}

variable "task_memory" {
  description = "Fargate task memory in MiB."
  type        = number
  default     = 2048
}

variable "desired_count" {
  description = "Normal ECS service task count."
  type        = number
}

variable "minimum_count" {
  description = "Minimum ECS autoscaling task count."
  type        = number
}

variable "maximum_count" {
  description = "Maximum ECS autoscaling task count."
  type        = number
}

variable "db_pool_size" {
  description = "Maximum PostgreSQL connections opened by each ECS application replica."
  type        = number

  validation {
    condition     = var.db_pool_size >= 2
    error_message = "db_pool_size must be at least two."
  }
}

variable "db_application_connection_budget" {
  description = "Maximum PostgreSQL connections reserved for all autoscaled application replicas."
  type        = number
}

variable "db_instance_class" {
  description = "RDS PostgreSQL instance class."
  type        = string
}

variable "db_multi_az" {
  description = "Enable synchronous Multi-AZ RDS standby and automatic failover."
  type        = bool
}

variable "db_backup_retention_days" {
  description = "RDS point-in-time recovery window."
  type        = number
}

variable "db_deletion_protection" {
  description = "Prevent accidental RDS deletion."
  type        = bool
}

variable "db_skip_final_snapshot" {
  description = "Skip a final snapshot when destroying RDS. Keep false in production."
  type        = bool
  default     = false
}

variable "cache_node_type" {
  description = "ElastiCache Redis node type."
  type        = string
}

variable "cache_node_count" {
  description = "Redis primary plus replica count. Must be at least two for failover."
  type        = number

  validation {
    condition     = var.cache_node_count >= 2
    error_message = "cache_node_count must be at least two."
  }
}

variable "cache_snapshot_retention_days" {
  description = "Redis automatic snapshot retention."
  type        = number
}

variable "log_retention_days" {
  description = "CloudWatch application, collector, VPC, and WAF log retention."
  type        = number
}

variable "alarm_email" {
  description = "Optional email address for SNS alarm notifications. Confirmation is manual."
  type        = string
  default     = ""
}

variable "global_rate_limit" {
  description = "Maximum requests from one IP in a five-minute WAF evaluation window."
  type        = number
  default     = 3000
}

variable "creation_rate_limit" {
  description = "Maximum URL creation requests from one IP in a five-minute window."
  type        = number
  default     = 100
}

variable "alb_deletion_protection" {
  description = "Prevent accidental ALB deletion."
  type        = bool
  default     = false
}

variable "adot_image" {
  description = "Pinned AWS Distro for OpenTelemetry collector image."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.48.0"
}

variable "tags" {
  description = "Additional tags applied to resources."
  type        = map(string)
  default     = {}
}
