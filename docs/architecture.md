# Architecture decisions

## Service shape

The project is one Spring Boot deployable organized by feature. A multi-module build or distributed services would add deployment and consistency costs without improving the current create-and-redirect scope.

PostgreSQL is the source of truth. Redis is a cache-aside optimization on the redirect read path and is never authoritative.

## Metadata flow

`GET /api/v1/urls/{shortCode}` validates the code and reads PostgreSQL directly. It deliberately bypasses Redis because the redirect cache contains only routing data, not complete authoritative metadata. Metadata reads neither warm the redirect cache nor create analytics events.

An expired mapping returns metadata with `expired: true` and `200 OK`. The mapping resource still exists even though following it returns `410 Gone`; this keeps the administrative metadata API useful without weakening redirect expiration behavior. Unknown codes return `404 Not Found`.

## Redirect flow

1. Validate `shortCode` against `^[A-Za-z0-9_-]{4,32}$`.
2. Read an expiry-aware JSON value from Redis.
3. On a cache miss, malformed cached value, or Redis failure, query PostgreSQL.
4. Return `404 Not Found` when no mapping exists.
5. Return `410 Gone` when the mapping exists but is expired. `410` communicates that the resource previously existed and is intentionally no longer available.
6. Cache an active database result. Redis TTL is the smaller of the configured cache TTL and remaining link lifetime.
7. Submit a best-effort analytics event.
8. Return `302 Found` with the destination in `Location`.

A primary-database outage on a cache miss returns `503 Service Unavailable`. Cache hits remain available during that outage; analytics persistence fails independently on its background executor.

`302` is preferred over `301` because permanent browser and intermediary caching would make future operational changes to a mapping difficult. The API does not currently expose mapping updates, but avoiding permanent client state is the safer default.

## Analytics reliability boundary

Successful redirects submit a minimal event containing only the short code and UTC timestamp. IP addresses and user-agent strings are intentionally not collected.

Events are written asynchronously through a bounded executor and a separate database transaction. Persistence failures and executor rejection are logged and do not alter the redirect response. Two Actuator counters expose successful and failed persistence:

- `url_shortener.redirect.analytics.recorded`
- `url_shortener.redirect.analytics.failures`

This is best-effort delivery. A transactional outbox plus durable broker would be appropriate if analytics completeness became a business requirement, but it is unnecessary infrastructure for this assessment.

`GET /api/v1/urls/{shortCode}/analytics` reads an indexed PostgreSQL aggregate and is therefore eventually consistent with recent redirects. It returns a zero count and null `lastAccessedAt` before the first persisted redirect. Analytics remain available for expired mappings because the mapping and event history still exist.

Because event capture is best-effort, these counts are operational/product analytics rather than a billing-grade ledger. The current indexed raw-event aggregate is appropriate for this assessment's scale. A production owner should define retention before launch; at high volume, time partitioning and periodic rollups would bound storage and query cost.

Referrer, user-agent, and IP address are intentionally not stored. Those values can contain identifying or sensitive information and require explicit product purpose, normalization, retention, and access-control policies. The minimum aggregate satisfies the current requirement without collecting data speculatively.

## Collision and alias behavior

Generated codes contain eight cryptographically random Base62 characters by default. Creation retries a bounded number of times when the database unique constraint detects a collision. Custom aliases are checked for a friendly error, but the unique constraint is the concurrency-safe authority; conflicts return `409 Conflict`.

Short codes and aliases are case-sensitive, preserving the full Base62 keyspace.

## Cache availability

Redis read, decode, and write failures fail open to PostgreSQL. Redis is excluded from aggregate health because cache loss is a degraded-performance condition, not loss of correctness or availability. PostgreSQL remains part of readiness through the datasource health contributor.

## URL security policy

Destinations must be absolute `http` or `https` URIs with a host and without embedded user credentials. The service does not fetch destinations, so private-network hosts are not an SSRF path from this server. Deployment-level abuse controls such as rate limiting, authentication for link creation, and reputation checks remain future concerns rather than hidden assumptions in this scope.
