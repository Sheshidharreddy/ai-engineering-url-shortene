resource "aws_ecs_cluster" "service" {
  name = local.name_prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = local.common_tags
}

resource "aws_ecs_cluster_capacity_providers" "service" {
  cluster_name       = aws_ecs_cluster.service.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "application" {
  name              = "/aws/ecs/${local.name_prefix}/application"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

resource "aws_cloudwatch_log_group" "collector" {
  name              = "/aws/ecs/${local.name_prefix}/adot"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}

resource "aws_iam_role" "ecs_execution" {
  name = "${local.name_prefix}-ecs-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "read-runtime-secrets"
  role = aws_iam_role.ecs_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.application.arn,
          aws_db_instance.postgres.master_user_secret[0].secret_arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.platform.arn]
      }
    ]
  })
}

resource "aws_iam_role" "ecs_task" {
  name = "${local.name_prefix}-ecs-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy" "ecs_task_telemetry" {
  name = "publish-telemetry"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "xray:PutTraceSegments",
          "xray:PutTelemetryRecords"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "aps:RemoteWrite",
          "aps:GetSeries",
          "aps:GetLabels",
          "aps:GetMetricMetadata"
        ]
        Resource = aws_prometheus_workspace.service.arn
      }
    ]
  })
}

locals {
  adot_configuration = <<-YAML
    extensions:
      sigv4auth:
        region: ${var.aws_region}
        service: aps
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318
      prometheus:
        config:
          scrape_configs:
            - job_name: url-shortener
              scrape_interval: 30s
              metrics_path: /internal/actuator/prometheus
              static_configs:
                - targets: ["127.0.0.1:8080"]
                  labels:
                    service: ${var.project_name}
                    environment: ${var.environment}
    processors:
      batch: {}
      memory_limiter:
        check_interval: 5s
        limit_mib: 384
        spike_limit_mib: 96
    exporters:
      awsxray: {}
      prometheusremotewrite:
        endpoint: ${aws_prometheus_workspace.service.prometheus_endpoint}api/v1/remote_write
        auth:
          authenticator: sigv4auth
    service:
      extensions: [sigv4auth]
      pipelines:
        traces:
          receivers: [otlp]
          processors: [memory_limiter, batch]
          exporters: [awsxray]
        metrics:
          receivers: [prometheus]
          processors: [memory_limiter, batch]
          exporters: [prometheusremotewrite]
  YAML
}

resource "aws_ecs_task_definition" "service" {
  family                   = local.name_prefix
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_cpu)
  memory                   = tostring(var.task_memory)
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name              = "application"
      image             = "${var.ecr_repository_url}:${var.image_tag}"
      essential         = true
      cpu               = floor(var.task_cpu * 0.75)
      memoryReservation = floor(var.task_memory * 0.70)
      portMappings = [{
        name          = "http"
        containerPort = 8080
        hostPort      = 8080
        protocol      = "tcp"
        appProtocol   = "http"
      }]
      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "aws" },
        { name = "DEPLOYMENT_ENVIRONMENT", value = var.environment },
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:${aws_db_instance.postgres.port}/url_shortener?sslmode=require&connectTimeout=3&socketTimeout=10&tcpKeepAlive=true" },
        { name = "DB_USERNAME", value = "url_shortener" },
        { name = "DB_POOL_SIZE", value = tostring(var.db_pool_size) },
        { name = "DB_MIN_IDLE", value = "2" },
        { name = "DB_CONNECT_TIMEOUT_SECONDS", value = "3" },
        { name = "DB_SOCKET_TIMEOUT_SECONDS", value = "10" },
        { name = "DB_CANCEL_TIMEOUT_SECONDS", value = "3" },
        { name = "DB_QUERY_TIMEOUT_MS", value = "5000" },
        { name = "DB_LOCK_TIMEOUT_MS", value = "3000" },
        { name = "REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "REDIS_SSL", value = "true" },
        { name = "BASE_URL", value = "https://${local.fqdn}" },
        { name = "MANAGEMENT_API_KEY_HEADER", value = "X-API-Key" },
        { name = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", value = "http://127.0.0.1:4318/v1/traces" },
        { name = "TRACING_SAMPLING_PROBABILITY", value = var.environment == "prod" ? "0.1" : "1.0" },
        { name = "SHUTDOWN_TIMEOUT", value = "45s" }
      ]
      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.postgres.master_user_secret[0].secret_arn}:password::" },
        { name = "REDIS_PASSWORD", valueFrom = "${aws_secretsmanager_secret.application.arn}:redis_password::" },
        { name = "MANAGEMENT_API_KEY", valueFrom = "${aws_secretsmanager_secret.application.arn}:management_api_key::" }
      ]
      dependsOn = [{
        containerName = "adot-collector"
        condition     = "START"
      }]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O - http://127.0.0.1:8080/internal/actuator/health/liveness || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 45
      }
      stopTimeout = 60
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.application.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "application"
        }
      }
    },
    {
      name              = "adot-collector"
      image             = var.adot_image
      essential         = false
      cpu               = floor(var.task_cpu * 0.25)
      memoryReservation = floor(var.task_memory * 0.25)
      environment = [{
        name  = "AOT_CONFIG_CONTENT"
        value = local.adot_configuration
      }]
      portMappings = [
        { containerPort = 4317, hostPort = 4317, protocol = "tcp" },
        { containerPort = 4318, hostPort = 4318, protocol = "tcp" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.collector.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "adot"
        }
      }
    }
  ])

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  tags = local.common_tags

  lifecycle {
    precondition {
      condition     = var.db_pool_size * var.maximum_count <= var.db_application_connection_budget
      error_message = "db_pool_size multiplied by maximum_count exceeds db_application_connection_budget."
    }
  }
}

resource "aws_ecs_service" "service" {
  name                               = local.name_prefix
  cluster                            = aws_ecs_cluster.service.id
  task_definition                    = aws_ecs_task_definition.service.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  availability_zone_rebalancing      = "ENABLED"
  enable_execute_command             = false
  wait_for_steady_state              = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  alarms {
    alarm_names = [
      aws_cloudwatch_metric_alarm.target_5xx.alarm_name,
      aws_cloudwatch_metric_alarm.no_healthy_targets.alarm_name
    ]
    enable   = true
    rollback = true
  }

  network_configuration {
    assign_public_ip = false
    security_groups  = [aws_security_group.app.id]
    subnets          = [for subnet in aws_subnet.app : subnet.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.service.arn
    container_name   = "application"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [
    aws_ecs_cluster_capacity_providers.service,
    aws_lb_listener.https,
    aws_iam_role_policy.ecs_execution_secrets
  ]

  tags = local.common_tags
}

resource "aws_appautoscaling_target" "service" {
  max_capacity       = var.maximum_count
  min_capacity       = var.minimum_count
  resource_id        = "service/${aws_ecs_cluster.service.name}/${aws_ecs_service.service.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${local.name_prefix}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.service.resource_id
  scalable_dimension = aws_appautoscaling_target.service.scalable_dimension
  service_namespace  = aws_appautoscaling_target.service.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "requests" {
  name               = "${local.name_prefix}-requests"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.service.resource_id
  scalable_dimension = aws_appautoscaling_target.service.scalable_dimension
  service_namespace  = aws_appautoscaling_target.service.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 1000
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.service.arn_suffix}/${aws_lb_target_group.service.arn_suffix}"
    }
  }
}
