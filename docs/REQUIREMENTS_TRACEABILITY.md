# Requirements Traceability

This document maps the assessment requirements in `Project_requirement.md` to the production implementation and its automated verification.

Status values:

- `PASS`: implemented and covered by an automated test or a direct static verification where a behavioral test is not applicable.
- `PARTIAL`: implemented but missing complete verification.
- `FAIL`: not implemented or behavior does not meet the requirement.

## URL Creation

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `POST /api/v1/urls` | `UrlController.create` | `UrlControllerTest.returnsCreatedResponseAndLocation`; `RedirectIntegrationTest.createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics` | PASS |
| HTTP `201 Created` | `UrlController.create` returns `ResponseEntity.created` | `UrlControllerTest.returnsCreatedResponseAndLocation` | PASS |
| Destination URL validation | `CreateUrlRequest`; `DestinationUrlValidator` | `DestinationUrlValidatorTest`; `UrlControllerTest.returnsValidationProblemForInvalidAlias` | PASS |
| HTTP/HTTPS only | `DestinationUrlValidator.ALLOWED_SCHEMES` | `DestinationUrlValidatorTest.rejectsUnsafeOrMalformedDestination` | PASS |
| Optional custom alias | `CreateUrlRequest.customAlias`; `UrlCreationService.createWithCustomAlias` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| Optional expiration | `CreateUrlRequest.expiresAt`; `UrlCreationService.validateExpiration` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| Generated short code | `SecureRandomShortCodeGenerator`; `UrlCreationService.createWithGeneratedCode` | `UrlCreationServiceTest.retriesGeneratedCodeAfterDatabaseCollision` | PASS |
| Database uniqueness constraint | Flyway constraint `uq_url_mappings_short_code`; `UrlMapping.shortCode` | `RedirectIntegrationTest.databaseEnforcesShortCodeUniqueness` | PASS |
| Duplicate custom alias handling | `UrlCreationService.createWithCustomAlias`; `AliasAlreadyExistsException`; `ApiExceptionHandler` | `UrlCreationServiceTest.returnsConflictWhenAliasAlreadyExists`; `UrlControllerTest.returnsConflictForDuplicateAlias` | PASS |
| Collision retry | `UrlCreationService.createWithGeneratedCode` uses bounded database-backed retries | `UrlCreationServiceTest.retriesGeneratedCodeAfterDatabaseCollision` | PASS |
| DTO response | `CreateUrlResponse` | `UrlControllerTest.returnsCreatedResponseAndLocation` | PASS |
| `createdAt` | `UrlMapping.createdAt`; `CreateUrlResponse.createdAt` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| `expiresAt` | `UrlMapping.expiresAt`; `CreateUrlResponse.expiresAt` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| `shortUrl` | `UrlMapper.toCreateResponse` uses configured base URL | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |

## Redirect

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `GET /{shortCode}` | `RedirectController.redirect` | `RedirectControllerTest.returnsFoundWithDestinationLocation`; `RedirectIntegrationTest.createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics` | PASS |
| Short-code validation | `ShortCodeValidator`; `UrlRedirectService.resolve` | `UrlRedirectServiceTest.rejectsMalformedCodeBeforeAccessingInfrastructure`; `RedirectControllerTest.returnsBadRequestForMalformedCode` | PASS |
| Redis lookup | `RedisUrlCache.find`; `UrlRedirectService.resolve` | `RedisUrlCacheTest.readsExpiryAwareValueFromRedis`; `UrlRedirectServiceTest.returnsUnexpiredRedisValueWithoutQueryingPostgres` | PASS |
| PostgreSQL fallback | `UrlRedirectDatabaseResolver.resolveAndCache` | `UrlRedirectServiceTest.queriesPostgresAndPopulatesRedisOnCacheMiss`; `UrlRedirectDatabaseResolverTest.resolvesLockedMappingAndPopulatesCache` | PASS |
| Expiration check | `CachedUrl.isExpiredAt`; `UrlMapping.isExpiredAt` | `UrlRedirectServiceTest.returnsGoneForExpiredCachedValue`; `UrlRedirectDatabaseResolverTest.rejectsExpiredMappingWithoutPopulatingCache` | PASS |
| Cache population | `UrlRedirectDatabaseResolver.resolveAndCache`; `RedisUrlCache.put` | `UrlRedirectDatabaseResolverTest.resolvesLockedMappingAndPopulatesCache`; `RedirectIntegrationTest.createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics` | PASS |
| Analytics invocation | `UrlRedirectService.recordAnalyticsWithoutAffectingRedirect` | `UrlRedirectServiceTest.returnsUnexpiredRedisValueWithoutQueryingPostgres`; `RedirectIntegrationTest.analyticsAggregatesPersistedRedirectEvents` | PASS |
| Analytics failure isolation | `UrlRedirectService` contains submission failures; `AsyncRedirectAnalyticsRecorder` contains persistence failures | `UrlRedirectServiceTest.analyticsSubmissionFailureDoesNotBreakRedirect`; `AsyncRedirectAnalyticsRecorderTest.persistenceFailureIsContainedAndCounted` | PASS |
| Redirect response | `RedirectController` returns HTTP `302` and `Location` | `RedirectControllerTest.returnsFoundWithDestinationLocation` | PASS |

## Metadata

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `GET /api/v1/urls/{shortCode}` | `UrlController.getMetadata`; `UrlMetadataService` | `UrlControllerTest.returnsUrlMetadata`; `RedirectIntegrationTest.metadataReadsPostgresWithoutPopulatingRedirectCacheOrAnalytics` | PASS |
| Correct active and expired response | `UrlMetadataResponse`; `UrlMetadataService.get` | `UrlMetadataServiceTest.returnsActiveMetadata`; `UrlMetadataServiceTest.returnsExpiredMetadataInsteadOfTreatingItAsMissing` | PASS |
| Metadata error handling | `ShortCodeValidator`; `UrlNotFoundException`; `ApiExceptionHandler` | `UrlMetadataServiceTest.returnsNotFoundForUnknownCode`; `UrlMetadataServiceTest.validatesCodeBeforeQueryingPostgres` | PASS |

## Analytics

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `GET /api/v1/urls/{shortCode}/analytics` | `UrlController.getAnalytics`; `UrlAnalyticsService` | `UrlControllerTest.returnsUrlAnalytics`; `RedirectIntegrationTest.analyticsAggregatesPersistedRedirectEvents` | PASS |
| `totalClickCount` | `RedirectEventRepository.summarizeByShortCode`; `UrlAnalyticsResponse` | `UrlAnalyticsServiceTest.returnsAggregatedAnalytics`; `RedirectIntegrationTest.analyticsAggregatesPersistedRedirectEvents` | PASS |
| `createdAt` | `UrlAnalyticsService` reads the current mapping creation time | `UrlAnalyticsServiceTest.returnsAggregatedAnalytics`; `UrlControllerTest.returnsUrlAnalytics` | PASS |
| `lastAccessedAt` | `MAX(RedirectEvent.occurredAt)` projection | `UrlAnalyticsServiceTest.returnsAggregatedAnalytics`; `UrlControllerTest.returnsAnalyticsWithNoLastAccessForUnusedUrl` | PASS |
| Successful redirects counted | `UrlRedirectService` invokes asynchronous recording only after successful resolution | `RedirectIntegrationTest.analyticsAggregatesPersistedRedirectEvents` | PASS |
| Failed redirects not counted | Unknown and expired paths throw before analytics invocation | `UrlRedirectServiceTest.returnsNotFoundWhenPostgresDoesNotContainCode`; `UrlRedirectServiceTest.returnsGoneForExpiredCachedValue`; `UrlRedirectDatabaseResolverTest.rejectsExpiredMappingWithoutPopulatingCache` | PASS |
| Analytics failure does not break redirect | Best-effort asynchronous recorder and bounded failure handling | `UrlRedirectServiceTest.analyticsSubmissionFailureDoesNotBreakRedirect`; `AsyncRedirectAnalyticsRecorderTest.persistenceFailureIsContainedAndCounted` | PASS |
| No unnecessary sensitive data retained | `RedirectEvent` stores only short code and occurrence timestamp | Static entity and migration review | PASS |

## Delete

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `DELETE /api/v1/urls/{shortCode}` | `UrlController.delete`; `UrlDeletionService`; `UrlDeletionWriter` | `UrlControllerTest.deletesUrlIdempotently`; `RedirectIntegrationTest.deleteRemovesMappingAnalyticsAndCacheAndAllowsSafeRetry` | PASS |
| Database behavior | Transaction deletes analytics before the mapping | `UrlDeletionWriterTest.deletesAnalyticsBeforeMapping`; `RedirectIntegrationTest.deleteRemovesMappingAnalyticsAndCacheAndAllowsSafeRetry` | PASS |
| Correct HTTP response | Idempotent HTTP `204 No Content` | `UrlControllerTest.deletesUrlIdempotently`; `RedirectIntegrationTest.deleteRemovesMappingAnalyticsAndCacheAndAllowsSafeRetry` | PASS |
| Redis invalidation | Strict eviction before and after the committed database deletion | `UrlDeletionServiceTest.evictsBeforeAndAfterDatabaseDeletion`; `RedisUrlCacheTest.evictsCachedUrl` | PASS |
| Nonexistent/deleted URL behavior | Bulk deletes are idempotent; endpoint remains `204` | `RedirectIntegrationTest.deleteRemovesMappingAnalyticsAndCacheAndAllowsSafeRetry` | PASS |
| Concurrent cache-miss deletion safety | Cache-miss read uses `PESSIMISTIC_READ` through cache population; delete waits and performs the last eviction | `RedirectIntegrationTest.concurrentCacheMissCannotRepopulateCacheAfterDeletion` | PASS |

## Health

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| Health endpoint | Actuator at `/internal/actuator/health` | `RedirectIntegrationTest.actuatorProbesAreAvailable` | PASS |
| Liveness | Actuator liveness group at `/internal/actuator/health/liveness` | `RedirectIntegrationTest.actuatorProbesAreAvailable` | PASS |
| Readiness | Actuator readiness group includes application state and PostgreSQL | `RedirectIntegrationTest.actuatorProbesAreAvailable`; Docker image `HEALTHCHECK` | PASS |

## Verification Command

Run the complete unit, web-layer, and Testcontainers suite with:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The Testcontainers integration class requires a compatible Docker daemon. CI explicitly fails if that class is skipped.

Latest local verification on August 12, 2026:

- `mvn --batch-mode --no-transfer-progress clean verify`: 57 non-container tests passed; the 10 container tests were skipped because the local Docker 20.10 daemon supports API 1.41 while docker-java initially requested API 1.44.
- `mvn --batch-mode --no-transfer-progress -Dapi.version=1.41 -Dtest=RedirectIntegrationTest test`: all 10 PostgreSQL and Redis Testcontainers tests passed with no failures, errors, or skips.
- Combined result: 67 tests passed, including the concurrent cache-miss deletion regression test.
