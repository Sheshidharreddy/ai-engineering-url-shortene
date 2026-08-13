# Engineering Scenarios

## 1. Greenfield URL shortener

**Requirement.** Build a production-oriented service that creates short URLs, redirects them, supports expiration and custom aliases, exposes metadata and analytics, and deletes mappings safely.

**Decomposition.** The work was divided into HTTP controllers, business services, persistence repositories, JPA entities, request/response DTOs, validation, exception translation, configuration, and mapping. PostgreSQL is authoritative; Redis is a cache and analytics uses a PostgreSQL outbox.

**Architecture and implementation.** `UrlController` owns management endpoints and `RedirectController` owns the public redirect. Services implement creation, redirect, metadata, analytics, and deletion flows. Flyway creates mapping, outbox, and event tables. Named database uniqueness constraints are the final authority for aliases, generated-code collisions, and creation idempotency.

**Tests and validation.** Unit tests cover validation, exact constraint classification, collision retries, idempotent replay, expiration, cache behavior, analytics failure isolation, and deletion ordering. MockMvc tests verify HTTP contracts. `RedirectIntegrationTest` exercises Flyway V2, PostgreSQL, Redis, concurrent outbox dispatch, retention, and simulated post-restart cold-cache load through Testcontainers. An earlier Docker Compose smoke test covered creation, redirect, metadata, analytics, expiration, deletion, cache invalidation, and health without an IDE; the current image rebuild was attempted but canceled after dependency resolution stalled, so no post-hardening Compose runtime pass is claimed.

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

**Failure behavior.** Redis read and decode failures are treated as cache misses and fall back to PostgreSQL. A Redis write failure does not fail the PostgreSQL-backed redirect. Deletion treats eviction failure as `503` because reporting success could leave a stale redirect. Cache hits continue to work during a PostgreSQL outage.

**Tests and trade-offs.** Unit tests cover hit, miss, malformed values, TTL capping, Redis failures, eviction, and concurrent miss coalescing. Integration tests cover cache population, malformed-value repair, cache-hit behavior, a real Redis outage with PostgreSQL fallback, expiration, concurrent deletion, and a simulated post-restart cold cache with one database load per replica. The added row lock affects only cache misses; single-flight coalescing is per replica rather than distributed.

## 3. Ambiguous analytics requirement

Before implementation, the engineer identified that the request "add analytics for shortened URLs" left key product, privacy, retention, and reliability decisions undefined. Those decisions were resolved explicitly as follows:

| Decision | Chosen behavior |
| --- | --- |
| What counts as a click? | A successfully resolved redirect submitted for analytics recording. |
| Are unknown or expired requests counted? | No; resolution fails before analytics submission. |
| What metadata is retained? | Short code and UTC occurrence time only. |
| Are IP, user-agent, or referrer stored? | No; they are unnecessary for the required aggregate and increase privacy obligations. |
| Can analytics failure break redirect? | No; outbox enqueue and dispatch failures are logged and counted, and neither changes the redirect response. |
| Consistency model | Durable after outbox commit and eventually consistent; an initial enqueue failure can still undercount. |
| Query result | Retained click events, mapping creation time, and latest retained successful redirect time. |
| Retention assumption | Raw events are deleted in bounded batches after 90 days by default and are also removed with the mapping. |

The engineer prioritized redirect reliability, crash tolerance, and data minimization. A PostgreSQL outbox was accepted instead of the volatile per-instance executor; an external broker, event partitioning, and richer visitor metadata remain deferred. Unit and integration tests verify successful counting, non-counting of unknown and expired redirects, `lastAccessedAt`, failure isolation, concurrent outbox consumers, and retention batching.

## 4. Reliability failure matrix

| Scenario | Behavior | Verification | Status |
| --- | --- | --- | --- |
| PostgreSQL unavailable | Cache misses return `503`; cache-hit destination resolution succeeds even if noncritical analytics enqueue fails after bounded pool/connect/socket/query deadlines | `RedirectControllerTest.returnsServiceUnavailableWhenPrimaryDatabaseCannotBeReached`; `UrlRedirectServiceTest.returnsUnexpiredRedisValueWithoutQueryingPostgres`; `UrlRedirectServiceTest.analyticsSubmissionFailureDoesNotBreakRedirect` | PASS |
| Redis unavailable | Read failures fall back to PostgreSQL, write failures leave the database-backed redirect successful, and strict deletion invalidation returns `503` | `RedirectIntegrationTest.redisOutageFallsBackToPostgresForRedirect`; `UrlControllerTest.returnsServiceUnavailableWhenCacheCannotBeInvalidated` | PASS |
| Duplicate alias | Friendly pre-check plus authoritative database uniqueness returns `409` | `UrlControllerTest.returnsConflictForDuplicateAlias`; `RedirectIntegrationTest.simultaneousCustomAliasCreationCreatesExactlyOneMapping` | PASS |
| Generated-code collision | Each failed write rolls back independently and bounded retries continue; exhaustion returns `503` | `RedirectIntegrationTest.generatedCodeCollisionRetriesAfterDatabaseConstraintFailure`; `UrlControllerTest.returnsServiceUnavailableWhenUniqueCodeCannotBeAllocated` | PASS |
| Invalid URL | Unsafe or malformed destinations return `400` before persistence | `DestinationUrlValidatorTest.rejectsUnsafeOrMalformedDestination` | PASS |
| Expired URL | Cache and database paths return `410` and do not record analytics | `UrlRedirectServiceTest.returnsGoneForExpiredCachedValue`; `RedirectIntegrationTest.expiredMappingReturnsGone` | PASS |
| Unknown URL | A syntactically valid missing code returns `404` and is not counted | `RedirectControllerTest.returnsNotFoundForUnknownCode`; `UrlRedirectServiceTest.returnsNotFoundWhenPostgresDoesNotContainCode` | PASS |
| Analytics persistence failure | Enqueue failure is counted and propagated to the redirect isolation boundary; committed outbox rows retry on dispatch failure | `UrlRedirectServiceTest.analyticsSubmissionFailureDoesNotBreakRedirect`; `DurableRedirectAnalyticsRecorderTest.enqueueFailureIsCountedAndPropagatedForRedirectBoundaryToContain`; `RedirectAnalyticsOutboxProcessorTest` | PASS |
| Malformed request | Invalid JSON returns a structured `400` problem response | `UrlControllerTest.returnsMalformedRequestProblemForInvalidJson` | PASS |
| Cache inconsistency | Malformed data is repaired; cold-cache concurrent requests coalesce to one database load per replica | `RedirectIntegrationTest.malformedCachedValueFallsBackToPostgresAndRepairsCache`; `RedirectIntegrationTest.simulatedColdCacheAfterRedisRestartProducesOneDatabaseLoadPerReplica` | PASS |
| Concurrent alias creation | Exactly one request creates the mapping and the other receives a conflict | `RedirectIntegrationTest.simultaneousCustomAliasCreationCreatesExactlyOneMapping` | PASS |

Failures that threaten correctness are surfaced as `4xx` or `503` responses. Redis read/write optimization failures and noncritical analytics failures are intentionally isolated because PostgreSQL remains authoritative and redirects are the primary path.

The datasource now has explicit Hikari acquisition/validation timeouts, pgJDBC connect/socket/cancel timeouts, session statement and lock timeouts, and a JPA query timeout. Terraform defines a pool size per replica and validates that maximum autoscaling capacity remains within the environment's application connection budget.

`UrlCreationService` inspects named PostgreSQL/Hibernate constraints. Only `uq_url_mappings_short_code` becomes an alias conflict or generated-code retry; unrelated integrity failures remain visible. `uq_url_mappings_idempotency_key` converges concurrent request retries on the committed mapping.
