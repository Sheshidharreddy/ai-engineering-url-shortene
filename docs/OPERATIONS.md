# Production Operations

## Service Objectives

| Signal | Target | Measurement |
| --- | --- | --- |
| Redirect availability | 99.9% per rolling 30 days | Non-`5xx` `http.server.requests` for `GET /{shortCode}` in Amazon Managed Service for Prometheus; expected `400`, `404`, and `410` are not failures. |
| Redirect latency | 95% below 250 ms; 99% below 750 ms | `http.server.requests` in Amazon Managed Service for Prometheus, split by redirect route. |
| Management API availability | 99.5% per rolling 30 days | Non-`5xx` responses for authenticated `/api/v1/urls/**` requests. |
| Deployment reliability | 99% successful releases | GitHub deployment workflow result excluding explicitly cancelled runs. |

The first production month is a calibration period. Validate traffic shape and adjust thresholds using measured data rather than weakening alerts during an incident.

## Alert Routing

Terraform creates an encrypted SNS alarm topic and optional email subscription. Before launch:

1. Confirm the SNS subscription email.
2. Route SNS to the organization's paging system for production.
3. Assign a primary and secondary on-call engineer.
4. Test one non-destructive alarm in dev and record delivery time.

Current alerts cover zero healthy targets, ALB target `5xx`, p95 latency, ECS CPU, RDS CPU/storage, and Redis CPU. The CloudWatch dashboard combines traffic, failures, latency, healthy tasks, compute, database, cache, WAF, and recent structured errors. Add production alert rules for sustained `url_shortener_redirect_analytics_enqueue_failures_total`, dispatch failures, and retention failures before treating analytics completeness as an SLO.

## Triage Sequence

1. Acknowledge the page and declare incident severity.
2. Check ALB healthy targets, ECS deployment events, and recent GitHub deployments.
3. Use the CloudWatch dashboard and application log group `/aws/ecs/sheshidhar-url-shortener-<environment>/application`.
4. Correlate an affected request with `trace.id` in structured logs and AWS X-Ray.
5. Check RDS connectivity, failover events, free storage, connections, and Performance Insights.
6. Check Redis failover events and replication status. Redirects can fall back to PostgreSQL, but deletion deliberately returns `503` when strict invalidation cannot complete.
7. Roll back the application before changing infrastructure when a release correlates with the incident.
8. Communicate impact, mitigation, and next update time; preserve logs and workflow evidence.

Do not enable ECS Exec or log destination URLs, query strings, API keys, database passwords, or Redis credentials during debugging.

## Rollback

ECS rolling deployments retain 100% minimum healthy capacity and create up to 200% capacity. The ECS circuit breaker and target-health/`5xx` deployment alarms roll back failed deployments. The GitHub workflow also captures the previous task definition and restores it when post-deployment stability or smoke tests fail.

For a manual emergency rollback:

```bash
aws ecs list-task-definitions \
  --family-prefix sheshidhar-url-shortener-prod \
  --sort DESC

aws ecs update-service \
  --cluster sheshidhar-url-shortener-prod \
  --service sheshidhar-url-shortener-prod \
  --task-definition PREVIOUS_TASK_DEFINITION_ARN \
  --force-new-deployment

aws ecs wait services-stable \
  --cluster sheshidhar-url-shortener-prod \
  --services sheshidhar-url-shortener-prod
```

Record the rollback in the incident timeline. The next Terraform plan will show the out-of-band task-definition drift.

## Deployment Draining

ECS removes a terminating task from the ALB target group, waits through the 45-second deregistration window, and sends `SIGTERM`. Spring changes readiness to refusing traffic and gives active requests up to 45 seconds to finish; ECS allows 60 seconds before force termination. During every UAT release, verify that old tasks receive no new requests, in-flight requests complete, and desired healthy capacity never drops below the configured minimum.

## Database Recovery

RDS automated backups provide point-in-time recovery for the configured retention window, and AWS Backup creates additional daily recovery points. At least quarterly:

1. Restore the latest production recovery point into an isolated subnet group under a temporary identifier.
2. Verify Flyway history, row counts, constraints, and representative create/redirect/analytics queries.
3. Record recovery point objective and measured recovery time objective.
4. Destroy the isolated restore only after evidence is retained.

Never test restoration by replacing the live database. Restores have not been executed merely because Terraform defines backups.

Application connection budgets are 24 in dev, 56 in UAT, and 160 in production. Compare Hikari active/pending connections and RDS `DatabaseConnections` against these budgets during load tests. Increase pool sizes only with a matching RDS capacity review and Terraform budget change.

## Redis Recovery

Redis is not the system of record. During failover, redirect cache misses fall back to PostgreSQL. After a sustained cache loss, monitor database load while Redis warms. Do not restore stale Redis snapshots over a healthy cluster solely to recover cached redirects. If strict deletion fails during failover, retry the idempotent deletion after Redis recovers.

The service coalesces concurrent cold misses for one short code per replica. With the production maximum of 12 replicas, one hot missing key can still cause up to 12 simultaneous PostgreSQL loads; include that multiplier in restart and failover load tests.

## Analytics Recovery

Committed `redirect_analytics_outbox` rows remain in PostgreSQL until one replica dispatches them. On dispatch failure, verify PostgreSQL health and the dispatch-failure counter before restarting tasks. Do not delete outbox rows to clear an alert. Raw events older than `ANALYTICS_RETENTION` are deleted in bounded batches; a retention failure increases storage but does not block redirects.

## Secret Rotation

- Rotate the management API key by updating the Secrets Manager JSON value and forcing a new ECS deployment. Coordinate client rollover because the current filter accepts one active key.
- Use RDS managed password rotation during a planned window and force new tasks so connection pools use the new credential.
- Redis auth-token rotation requires a staged ElastiCache rotation and ECS redeployment; test it in dev before production.
- Never expose secret values in Terraform output, GitHub logs, shell tracing, or incident tickets.

Automatic rotation is not enabled because database, Redis, and client cutover require coordinated validation. Secrets Manager is the storage and delivery mechanism, not a substitute for a tested rotation procedure.

## Resilience Exercises

Before launch and at least twice yearly, execute and record:

- stop one ECS task and confirm capacity replacement without failed smoke tests;
- force an RDS Multi-AZ failover and measure redirect/cache-miss impact;
- force Redis primary failover and verify PostgreSQL fallback plus delete retry behavior;
- block Redis from a test task and verify redirect fallback;
- restore RDS from backup and validate schema/data;
- deploy a known-bad health check in dev and confirm automatic rollback;
- page the on-call route and verify acknowledgment.

## Regional Failure

The platform is highly available across availability zones within one AWS region. It is not multi-region. A regional disaster requires a separately designed warm standby, cross-region data replication, DNS failover, and regularly tested recovery procedure.
