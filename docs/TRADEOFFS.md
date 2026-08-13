# Trade-offs

| Decision | Benefit | Cost or limitation |
| --- | --- | --- |
| Single Spring Boot service | Simple deployment and transactional boundaries | Independent scaling of management and redirect paths is not available. |
| PostgreSQL as source of truth | Strong uniqueness and deletion correctness | Cache misses depend on database availability. |
| Redis cache-aside | Fast redirects and cache-hit availability during database outages | Requires expiry-aware values and careful invalidation. |
| Pessimistic read lock on cache miss | Orders cache population before deletion's final eviction | Concurrent deletes may wait for a cache-miss transaction; hits remain lock-free. |
| Strict deletion invalidation | Avoids reporting success while stale redirects may remain | Redis outage makes deletion return `503` even if PostgreSQL is healthy. |
| Double eviction | Narrows stale-cache windows around the database transaction | Adds two Redis operations and is insufficient alone without lock ordering. |
| HTTP `302 Found` | Avoids permanent client caching of mappings | Clients may repeat redirect requests more often than with `301`. |
| Random Base62 codes | Large keyspace without a central sequence | Collisions remain possible and require bounded database-backed retries. |
| Asynchronous best-effort analytics | Analytics latency and failure do not break redirects | Counts can lag or lose events and are not suitable for billing. |
| Raw event aggregation | Simple implementation and exact timestamps at current scale | Storage and aggregate cost grow with traffic; retention and rollups are future operational decisions. |
| Minimal analytics fields | Reduces privacy and security exposure | No geographic, device, campaign, or referrer analysis. |
| One existing Flyway migration | Preserves compatibility with databases where `V1` is already applied | The schema is not split into one migration per table. Future changes must use new versioned migrations. |
| Actuator under `/internal/actuator` | Separates operational routes from the public API namespace | Deployment must restrict network access; the application does not add authentication in this scope. |
