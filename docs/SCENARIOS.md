# Engineering Scenarios

## 1. Greenfield URL shortener

**Requirement.** Build a production-oriented service that creates short URLs, redirects them, supports expiration and custom aliases, exposes metadata and analytics, and deletes mappings safely.

**Decomposition.** The work was divided into HTTP controllers, business services, persistence repositories, JPA entities, request/response DTOs, validation, exception translation, configuration, and mapping. PostgreSQL is authoritative; Redis and asynchronous analytics are secondary concerns.

**Architecture and implementation.** `UrlController` owns management endpoints and `RedirectController` owns the public redirect. Services implement creation, redirect, metadata, analytics, and deletion flows. Flyway creates the mapping and event tables. A database uniqueness constraint is the final authority for aliases and generated-code collisions.

**Tests and validation.** Unit tests cover validation, collision retries, expiration, cache behavior, analytics failure isolation, and deletion ordering. MockMvc tests verify HTTP contracts. `RedirectIntegrationTest` exercises the application with real PostgreSQL and Redis through Testcontainers.

**Risks.** Collision races, unsafe URL schemes, stale cache entries, dependency outages, and analytics failures were treated as correctness or reliability risks. Authentication, abuse prevention, and billing-grade analytics remain outside the assessment scope and are documented rather than hidden.

**AI assistance and engineer decisions.** AI helped inspect requirements, identify missing traceability and documentation, and review concurrency behavior. The engineer retained the `com.sheshidhar.urlshortener` namespace, selected the layered package structure, preserved the already-applied Flyway migration name, and required real integration validation before accepting the locking solution.

## 2. Brownfield Redis enhancement

**Before**

```text
Redirect -> PostgreSQL -> destination
```

**After**

```text
Redirect -> Redis hit -> destination
         -> Redis miss/failure -> PostgreSQL -> Redis population -> destination
```

**Impacted modules.** The redirect service gained `UrlCache`; `RedisUrlCache` encapsulates serialization and Redis operations; `UrlRedirectDatabaseResolver` owns the transactional PostgreSQL fallback; deletion gained strict cache invalidation; configuration gained Redis connection and TTL settings.

**TTL and expiration.** Cached values include `expiresAt`. Redis TTL is the smaller of the configured cache TTL and the remaining URL lifetime, and cached values are checked again before redirecting.

**Invalidation and concurrency.** Deletion evicts before and after the database transaction. Cache-miss reads hold a PostgreSQL pessimistic read lock through cache population, forcing deletion to wait and then perform the final eviction. This prevents an older in-flight miss from restoring a deleted or reused alias.

**Failure behavior.** Redis read, decode, and write failures fall back to PostgreSQL. Deletion treats eviction failure as `503` because reporting success could leave a stale redirect. Cache hits continue to work during a PostgreSQL outage.

**Tests and trade-offs.** Unit tests cover hit, miss, TTL capping, Redis failures, and eviction. Integration tests cover population, malformed values, Redis outage, and concurrent deletion. The added lock affects only cache misses; it trades some miss-path concurrency for deletion correctness.

## 3. Ambiguous analytics requirement

The request "add analytics for shortened URLs" leaves key product and reliability decisions undefined. The implementation resolves the ambiguity as follows:

| Decision | Chosen behavior |
| --- | --- |
| What counts as a click? | A successfully resolved redirect submitted for analytics recording. |
| Are unknown or expired requests counted? | No; resolution fails before analytics submission. |
| What metadata is retained? | Short code and UTC occurrence time only. |
| Are IP, user-agent, or referrer stored? | No; they are unnecessary for the required aggregate and increase privacy obligations. |
| Can analytics failure break redirect? | No; submission and persistence failures are isolated and measured. |
| Consistency model | Eventually consistent, best effort, not billing-grade. |
| Query result | Total clicks, mapping creation time, and last successful access time. |
| Retention assumption | Events are removed when the mapping is deleted; broader time-based retention requires a product policy. |

The engineer prioritized redirect reliability and data minimization. A durable outbox, message broker, event partitioning, and richer visitor metadata were rejected as unnecessary for the stated assessment.
