# Trade-offs

| Decision | Benefit | Cost or limitation |
| --- | --- | --- |
| Single Spring Boot service | Simple deployment and transactional boundaries | Independent scaling of management and redirect paths is not available. |
| PostgreSQL as source of truth | Strong uniqueness and deletion correctness | Cache misses depend on database availability. |
| Redis cache-aside | Fast redirects and cache-hit availability during database outages | Requires expiry-aware values and careful invalidation. |
| Per-replica cache-miss single-flight | Limits a cold key to one concurrent PostgreSQL load per replica | Does not coalesce misses across replicas; worst-case load is one query per active replica. |
| Pessimistic read lock on cache miss | Orders cache population before deletion's final eviction | Concurrent deletes may wait for a cache-miss transaction; hits remain lock-free. |
| Strict deletion invalidation | Avoids reporting success while stale redirects may remain | Redis outage makes deletion return `503` even if PostgreSQL is healthy. |
| Double eviction | Narrows stale-cache windows around the database transaction | Adds two Redis operations and is insufficient alone without lock ordering. |
| HTTP `302 Found` | Avoids permanent client caching of mappings | Clients may repeat redirect requests more often than with `301`. |
| Random Base62 codes | Large keyspace without a central sequence | Collisions remain possible and require bounded database-backed retries. |
| PostgreSQL analytics outbox | Committed events survive task crashes and competing replicas drain without duplicates | Initial enqueue adds a noncritical database write to redirects and can undercount when PostgreSQL is unavailable. |
| Raw event aggregation with 90-day retention | Preserves exact retained timestamps while bounding storage growth | Counts cover the retention window; partitioning or rollups are still needed at substantially higher volume. |
| Creation idempotency key on mapping | Safe client retries converge without a separate idempotency service | State is removed with the mapping, so replay after deletion can create a new mapping. |
| Constraint-name classification | Retries only genuine short-code collisions | Couples failure translation to stable migration constraint names. |
| Minimal analytics fields | Reduces privacy and security exposure | No geographic, device, campaign, or referrer analysis. |
| One existing Flyway migration | Preserves compatibility with databases where `V1` is already applied | The schema is not split into one migration per table. Future changes must use new versioned migrations. |
| Actuator under `/internal/actuator` | Supports ALB health and task-local Prometheus scraping | It is unauthenticated inside the task; the AWS listener must continue blocking public forwarding. |
| Environment API key | Protects management APIs without introducing a user system | It is shared authorization, not identity, ownership, or fine-grained policy; rotation requires coordinated client rollout. |
| Multi-AZ ECS/RDS/Redis | Survives common task, node, and availability-zone failures | Higher baseline cost and no protection from a complete regional outage. |
| One NAT gateway per production AZ | Removes a single cross-zone egress dependency | NAT gateways and data processing add material recurring cost. |
| WAF managed rules and rate limits | Adds perimeter abuse, input, IP reputation, and traffic controls | Rules need tuning; caller reputation does not determine whether a submitted destination is phishing. |
| ADOT with AMP and X-Ray | Produces Prometheus metrics and distributed traces without embedding AWS SDKs | Adds sidecar resource consumption and managed telemetry cost. |
| Rolling deployment with automatic rollback | Maintains capacity and reverses unhealthy releases | Database migrations must remain backward compatible because old and new tasks overlap. |
| Per-replica database pool budgets | Prevents autoscaling from exhausting the database connection allocation | Conservative pools may queue requests before CPU is saturated and require load-based tuning. |
| GitHub OIDC deploy role | Removes long-lived AWS access keys from CI | Terraform's broad provisioning surface still requires a highly privileged role best isolated per account. |
