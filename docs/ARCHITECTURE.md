# Architecture

## Service shape

The project is one Spring Boot deployable organized into controller, service, repository, entity, DTO, exception, configuration, validation, and mapper layers under `com.sheshidhar.urlshortener`. A multi-module build or distributed services would add deployment and consistency costs without improving the current create-and-redirect scope.

Controllers contain HTTP concerns only. Services coordinate business flows, repositories and transactional writers isolate persistence, DTOs remain separate from JPA entities, validators enforce input policy, the exception advice translates failures to problem details, and `UrlMapper` centralizes response construction.

PostgreSQL is the source of truth. Redis is a cache-aside optimization on the redirect read path and is never authoritative.

## Production deployment

Terraform deploys the service to private ECS Fargate subnets across multiple availability zones. Production keeps three tasks and a minimum autoscaling capacity of three, enables availability-zone rebalancing, and places an internet-facing ALB in front of the tasks. The ALB terminates TLS with ACM, redirects HTTP to HTTPS, blocks public Actuator access, and is associated with AWS WAF.

RDS PostgreSQL uses Multi-AZ automatic failover, TLS, encryption, managed credentials, automatic backups, AWS Backup, Performance Insights, and isolated data subnets. ElastiCache Redis uses a primary and replicas across availability zones, automatic failover, TLS, authentication, encryption, and snapshots. Production uses one NAT gateway per availability zone so loss of one zone does not remove all application egress.

The application runs as a stateless rolling ECS service. Deployment minimum/maximum healthy percentages are 100/200, and target health, ECS deployment circuit breaking, CloudWatch deployment alarms, and workflow-level rollback protect releases. Spring graceful shutdown marks readiness as refusing traffic and drains in-flight requests for up to 45 seconds; ALB deregistration is 45 seconds and ECS allows 60 seconds before force termination. This is regional multi-AZ high availability, not multi-region disaster recovery.

The production request path is:

```text
Route53 -> HTTPS ALB -> WAF -> ECS Fargate -> Redis cache-aside -> PostgreSQL authority
```

Application metrics are exposed in Prometheus format to a task-local ADOT sidecar and written to Amazon Managed Service for Prometheus. Traces use OTLP to the same sidecar and AWS X-Ray. ECS JSON logs, WAF logs, and rejected VPC flow logs are centralized in CloudWatch with environment-specific retention.

## Metadata flow

`GET /api/v1/urls/{shortCode}` validates the code and reads PostgreSQL directly. It deliberately bypasses Redis because the redirect cache contains only routing data, not complete authoritative metadata. Metadata reads neither warm the redirect cache nor create analytics events.

An expired mapping returns metadata with `expired: true` and `200 OK`. The mapping resource still exists even though following it returns `410 Gone`; this keeps the administrative metadata API useful without weakening redirect expiration behavior. Unknown codes return `404 Not Found`.

## Deletion flow

`DELETE /api/v1/urls/{shortCode}` is idempotent and returns `204 No Content` even when the mapping is already absent. Redis is evicted before and after the committed PostgreSQL transaction. Cache invalidation is strict for deletion: failure returns `503 Service Unavailable`, allowing a retry to repair incomplete work.

A redirect cache miss reads the mapping with a PostgreSQL pessimistic read lock and holds that lock through Redis cache population. The delete transaction must acquire a conflicting row lock, so it waits for any in-flight database-backed redirect to finish its cache write. Deletion then commits and performs the final Redis eviction. This ordering prevents a delayed cache-miss request from repopulating a deleted mapping after invalidation, including when the same custom alias is later reused.

Cache-hit destination resolution does not acquire a mapping row lock. Analytics enqueue is a separate, noncritical PostgreSQL write and its failure cannot change the resolved redirect. A cache-hit request already in progress may complete while deletion is running, but after deletion returns, the final eviction has removed the cached mapping and no earlier database reader can repopulate it.

The database transaction deletes pending analytics outbox rows, persisted events, and then the mapping. This prevents a reused custom alias from inheriting a previous mapping's click history and minimizes retained data. Analytics aggregation also filters events older than the current mapping's `createdAt`, protecting alias reuse from a late event written by an in-flight redirect.

## Redirect flow

1. Validate `shortCode` against `^[A-Za-z0-9_-]{4,32}$`.
2. Read an expiry-aware JSON value from Redis.
3. On a cache hit, validate expiration and resolve the destination without a PostgreSQL mapping lookup.
4. On a cache miss, malformed cached value, or Redis failure, coalesce concurrent requests for that code to one PostgreSQL load per replica and query with a pessimistic read lock.
5. Return `404 Not Found` when no mapping exists.
6. Return `410 Gone` when the mapping exists but is expired. `410` communicates that the resource previously existed and is intentionally no longer available.
7. Cache an active database result while the row lock is held. Redis TTL is the smaller of the configured cache TTL and remaining link lifetime.
8. Commit the read transaction and release the row lock.
9. Attempt to persist a minimal analytics row in the durable PostgreSQL outbox.
10. Return `302 Found` with the destination in `Location`.

A primary-database outage on a cache miss returns `503 Service Unavailable`. Cache-hit destination resolution remains available during that outage; analytics enqueue failure is contained after the configured pool, connect, socket, and query deadlines.

`302` is preferred over `301` because permanent browser and intermediary caching would make future operational changes to a mapping difficult. The API does not currently expose mapping updates, but avoiding permanent client state is the safer default.

## Analytics reliability boundary

Successful redirects submit a minimal event containing only the short code and UTC timestamp. IP addresses and user-agent strings are intentionally not collected.

Each successful resolution attempts to write an outbox row in a separate transaction. Once committed, the row survives process termination. Scheduled dispatchers on all replicas claim batches with `FOR UPDATE SKIP LOCKED`, insert raw redirect events, and remove the claimed outbox rows atomically. Competing replicas can drain the queue without duplicate events.

An outbox enqueue failure is logged and does not alter the redirect response. The following Actuator counters expose enqueue, dispatch, and retention behavior:

- `url_shortener.redirect.analytics.enqueued`
- `url_shortener.redirect.analytics.enqueue.failures`
- `url_shortener.redirect.analytics.recorded`
- `url_shortener.redirect.analytics.dispatch.failures`
- `url_shortener.redirect.analytics.retention.deleted`
- `url_shortener.redirect.analytics.retention.failures`

Delivery is durable after the initial outbox commit, but it is not a billing ledger: a database outage before enqueue can still undercount. A broker such as SQS or Kafka would further decouple cache-hit latency from PostgreSQL if analytics volume or completeness requirements justify another managed dependency.

`GET /api/v1/urls/{shortCode}/analytics` reads an indexed PostgreSQL aggregate and is therefore eventually consistent with recent redirects. It returns a zero count and null `lastAccessedAt` before the first persisted redirect. Analytics remain available for expired mappings because the mapping and event history still exist.

The current indexed raw-event aggregate is appropriate for the expected launch scale. A scheduled job deletes events older than `ANALYTICS_RETENTION` in bounded batches, using a dedicated global occurrence-time index. The default is 90 days, so `totalClickCount` covers the retained window. Time partitioning or daily rollups remain the next step if event volume makes raw aggregation expensive.

Referrer, user-agent, and IP address are intentionally not stored. Those values can contain identifying or sensitive information and require explicit product purpose, normalization, retention, and access-control policies. The minimum aggregate satisfies the current requirement without collecting data speculatively.

## Collision and alias behavior

Generated codes contain eight cryptographically random Base62 characters by default. Creation retries only when the named short-code unique constraint detects a collision; unrelated check, length, and integrity failures are not misreported as collisions. Custom aliases are checked for a friendly error, but the unique constraint is the concurrency-safe authority; conflicts return `409 Conflict`.

Clients may provide an `Idempotency-Key`. The mapping stores the key and a SHA-256 fingerprint of the normalized creation request under a second named unique constraint. A matching replay returns the original mapping, a different request returns `409 Conflict`, and concurrent retries converge on the committed row. Idempotency state follows mapping lifecycle and is removed on deletion.

Short codes and aliases are case-sensitive, preserving the full Base62 keyspace.

## Cache availability

Redis read or decode failures become cache misses and fall back to PostgreSQL. A Redis write failure leaves the PostgreSQL-backed redirect successful without caching it. Per-replica single-flight coalescing limits a cold key to one concurrent database load per replica after Redis data loss; it does not coordinate across replicas. Redis is excluded from aggregate health because cache loss is a degraded-performance condition, not loss of correctness or availability. PostgreSQL remains part of readiness through the datasource health contributor.

PostgreSQL operations have explicit Hikari acquisition and validation timeouts, pgJDBC connect/socket/cancel timeouts, session `statement_timeout` and `lock_timeout`, and a JPA query timeout. Terraform gives each environment a per-replica pool size and an application-wide connection budget calculated against maximum autoscaling capacity.

## Health model

Actuator exposes `/internal/actuator/health`, `/internal/actuator/health/liveness`, and `/internal/actuator/health/readiness`. Liveness contains only Spring's application liveness state so an external dependency outage does not cause restart loops. Readiness explicitly includes the application readiness state and PostgreSQL; Redis remains excluded because redirects can fall back to PostgreSQL. During graceful shutdown Spring changes readiness to refusing traffic before the web server finishes draining. Operations that require strict cache invalidation, such as deletion, return their own `503` when Redis is unavailable.

## URL security policy

Destinations must be absolute `http` or `https` URIs with a host and without embedded user credentials. The service does not fetch destinations, so private-network hosts are not an SSRF path from this server. In AWS, management routes require a Secrets Manager-backed API key, WAF applies rate limits and AWS managed input/IP reputation controls, and the public ALB cannot reach Actuator routes. The key is environment-level authorization, not user identity or ownership. A public anonymous creation product would still require destination reputation classification and a staffed abuse workflow.
