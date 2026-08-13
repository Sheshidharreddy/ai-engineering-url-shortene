output "service_url" {
  description = "Public HTTPS service URL."
  value       = "https://${local.fqdn}"
}

output "service_fqdn" {
  value = local.fqdn
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.service.name
}

output "ecs_service_name" {
  value = aws_ecs_service.service.name
}

output "target_group_arn" {
  value = aws_lb_target_group.service.arn
}

output "application_secret_arn" {
  description = "Secret containing the Redis credential and management API key."
  value       = aws_secretsmanager_secret.application.arn
}

output "prometheus_workspace_endpoint" {
  value = aws_prometheus_workspace.service.prometheus_endpoint
}

output "alarm_topic_arn" {
  value = aws_sns_topic.alarms.arn
}
