# Engineering Scenarios

## 1. Greenfield URL shortener

**Requirement.** Build a production-oriented service that creates short URLs, redirects them, supports expiration and custom aliases, exposes metadata and analytics, and deletes mappings safely.

**Decomposition.** The work was divided into HTTP controllers, business services, persistence repositories, JPA entities, request/response DTOs, validation, exception translation, configuration, and mapping. PostgreSQL is authoritative; Redis and asynchronous analytics are secondary concerns.

**Architecture and implementation.** `UrlController` owns management endpoints and `RedirectController` owns the public redirect. Services implement creation, redirect, metadata, analytics, and deletion flows. Flyway creates the mapping and event tables. A database uniqueness constraint is the final authority for aliases and generated-code collisions.

**Tests and validation.** Unit tests cover validation, collision retries, expiration, cache behavior, analytics failure isolation, and deletion ordering. MockMvc tests verify HTTP contracts. `RedirectIntegrationTest` exercises the application with real PostgreSQL and Redis through Testcontainers. The complete suite passes 83 tests, and the Docker Compose smoke test verifies creation, redirect, metadata, analytics, expiration, deletion, cache invalidation, and health without an IDE.

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

**Tests and trade-offs.** Unit tests cover hit, miss, malformed values, TTL capping, Redis failures, and eviction. Integration tests cover cache population, malformed-value repair, cache-hit behavior, a real Redis outage with PostgreSQL fallback, expiration, and concurrent deletion with real PostgreSQL and Redis. The added lock affects only cache misses; it trades some miss-path concurrency for deletion correctness.

## 3. Ambiguous analytics requirement

Before implementation, the engineer identified that the request "add analytics for shortened URLs" left key product, privacy, retention, and reliability decisions undefined. Those decisions were resolved explicitly as follows:

| Decision | Chosen behavior |
| --- | --- |
| What counts as a click? | A successfully resolved redirect submitted for analytics recording. |
| Are unknown or expired requests counted? | No; resolution fails before analytics submission. |
| What metadata is retained? | Short code and UTC occurrence time only. |
| Are IP, user-agent, or referrer stored? | No; they are unnecessary for the required aggregate and increase privacy obligations. |
| Can analytics failure break redirect? | No; submission failures are logged, while persistence failures are logged and counted. Both are isolated from the redirect response. |
| Consistency model | Eventually consistent, best effort, not billing-grade. |
| Query result | Total persisted click events, mapping creation time, and latest persisted successful redirect time. |
| Retention assumption | Events are removed when the mapping is deleted; broader time-based retention requires a product policy. |

The engineer prioritized redirect reliability and data minimization. A durable outbox, message broker, event partitioning, and richer visitor metadata were rejected as unnecessary for the stated assessment. Unit and integration tests verify successful counting, non-counting of unknown and expired redirects, `lastAccessedAt`, and failure isolation; because persistence is best effort, reported analytics can undercount when recording fails.

## 4. Reliability failure matrix

| Scenario | Behavior | Verification | Status |
| --- | --- | --- | --- |
| PostgreSQL unavailable | Connection failures return `503`; cache hits do not synchronously query PostgreSQL, but query/socket duration is not globally bounded | `RedirectControllerTest.returnsServiceUnavailableWhenPrimaryDatabaseCannotBeReached`; `UrlRedirectServiceTest.returnsUnexpiredRedisValueWithoutQueryingPostgres` | PARTIAL |
| Redis unavailable | Read failures fall back to PostgreSQL, write failures leave the database-backed redirect successful, and strict deletion invalidation returns `503` | `RedirectIntegrationTest.redisOutageFallsBackToPostgresForRedirect`; `UrlControllerTest.returnsServiceUnavailableWhenCacheCannotBeInvalidated` | PASS |
| Duplicate alias | Friendly pre-check plus authoritative database uniqueness returns `409` | `UrlControllerTest.returnsConflictForDuplicateAlias`; `RedirectIntegrationTest.simultaneousCustomAliasCreationCreatesExactlyOneMapping` | PASS |
| Generated-code collision | Each failed write rolls back independently and bounded retries continue; exhaustion returns `503` | `RedirectIntegrationTest.generatedCodeCollisionRetriesAfterDatabaseConstraintFailure`; `UrlControllerTest.returnsServiceUnavailableWhenUniqueCodeCannotBeAllocated` | PASS |
| Invalid URL | Unsafe or malformed destinations return `400` before persistence | `DestinationUrlValidatorTest.rejectsUnsafeOrMalformedDestination` | PASS |
| Expired URL | Cache and database paths return `410` and do not record analytics | `UrlRedirectServiceTest.returnsGoneForExpiredCachedValue`; `RedirectIntegrationTest.expiredMappingReturnsGone` | PASS |
| Unknown URL | A syntactically valid missing code returns `404` and is not counted | `RedirectControllerTest.returnsNotFoundForUnknownCode`; `UrlRedirectServiceTest.returnsNotFoundWhenPostgresDoesNotContainCode` | PASS |
| Analytics persistence failure | Submission rejection is logged; persistence failure is logged and counted; neither changes the redirect | `UrlRedirectServiceTest.analyticsSubmissionFailureDoesNotBreakRedirect`; `AsyncRedirectAnalyticsRecorderTest.persistenceFailureIsContainedAndCounted` | PASS |
| Malformed request | Invalid JSON returns a structured `400` problem response | `UrlControllerTest.returnsMalformedRequestProblemForInvalidJson` | PASS |
| Cache inconsistency | Malformed data falls back to PostgreSQL and is replaced with a valid cache entry | `RedirectIntegrationTest.malformedCachedValueFallsBackToPostgresAndRepairsCache` | PASS |
| Concurrent alias creation | Exactly one request creates the mapping and the other receives a conflict | `RedirectIntegrationTest.simultaneousCustomAliasCreationCreatesExactlyOneMapping` | PASS |

Failures that threaten correctness are surfaced as `4xx` or `503` responses. Redis read/write optimization failures and best-effort analytics failures are intentionally isolated because PostgreSQL remains authoritative and redirects are the primary path.

The datasource has a three-second Hikari pool-acquisition timeout, but no PostgreSQL driver socket timeout or global query timeout. A refused connection is surfaced correctly; a half-open connection or stalled query can exceed the intended response window. Choosing those timeouts requires an operational latency target and should be applied as an explicit production configuration decision.

`UrlCreationService` currently interprets every `DataIntegrityViolationException` during creation as a short-code uniqueness collision. The expected collision paths are tested, but an unrelated database check or length violation could be reported as an alias conflict or retry exhaustion. Constraint-specific classification remains an open reliability improvement.
