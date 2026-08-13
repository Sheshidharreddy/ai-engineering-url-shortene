# Requirements Traceability

This document maps the assessment requirements in `Project_requirement.md` to the production implementation and its automated verification.

Status values:

- `PASS`: implemented and covered by an automated test or a direct static verification where a behavioral test is not applicable.
- `PARTIAL`: implemented but missing complete verification.
- `FAIL`: not implemented or behavior does not meet the requirement.

## Audit Summary

| Result | Count |
| --- | ---: |
| PASS | 43 |
| PARTIAL | 0 |
| FAIL | 0 |

Every minimum requirement listed in Step 2 has a corresponding implementation and automated test, except the sensitive-data-retention requirement, where direct entity and migration inspection is the appropriate verification. No assessment requirement is unmapped.

## URL Creation

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `POST /api/v1/urls` | `UrlController.create` | `UrlControllerTest.returnsCreatedResponseAndLocation`; `RedirectIntegrationTest.createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics` | PASS |
| HTTP `201 Created` | `UrlController.create` returns `ResponseEntity.created` | `UrlControllerTest.returnsCreatedResponseAndLocation` | PASS |
| Destination URL validation | `CreateUrlRequest`; `DestinationUrlValidator` | `DestinationUrlValidatorTest`; `UrlControllerTest.returnsValidationProblemForOversizedUrl` | PASS |
| HTTP/HTTPS only | `DestinationUrlValidator.ALLOWED_SCHEMES` | `DestinationUrlValidatorTest.acceptsAbsoluteHttpUrl`; `DestinationUrlValidatorTest.acceptsAndTrimsAbsoluteHttpsUrl`; `DestinationUrlValidatorTest.rejectsUnsafeOrMalformedDestination` | PASS |
| Optional custom alias | `CreateUrlRequest.customAlias`; `UrlCreationService.createWithCustomAlias` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| Optional expiration | `CreateUrlRequest.expiresAt`; `UrlCreationService.validateExpiration` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse`; `UrlCreationServiceTest.rejectsExpirationThatIsNotInTheFuture` | PASS |
| Generated short code | `SecureRandomShortCodeGenerator`; `UrlCreationService.createWithGeneratedCode` | `SecureRandomShortCodeGeneratorTest.generatesConfiguredLengthBase62Code`; `UrlCreationServiceTest.retriesGeneratedCodeAfterDatabaseCollision` | PASS |
| Database uniqueness constraint | Flyway constraint `uq_url_mappings_short_code`; `UrlMapping.shortCode` | `RedirectIntegrationTest.databaseEnforcesShortCodeUniqueness`; `RedirectIntegrationTest.simultaneousCustomAliasCreationCreatesExactlyOneMapping` | PASS |
| Duplicate custom alias handling | `UrlCreationService.createWithCustomAlias`; `AliasAlreadyExistsException`; `ApiExceptionHandler` | `UrlCreationServiceTest.returnsConflictWhenAliasAlreadyExists`; `UrlControllerTest.returnsConflictForDuplicateAlias`; `RedirectIntegrationTest.simultaneousCustomAliasCreationCreatesExactlyOneMapping` | PASS |
| Collision retry | `UrlCreationService.createWithGeneratedCode` uses bounded database-backed retries | `UrlCreationServiceTest.retriesGeneratedCodeAfterDatabaseCollision`; `UrlCreationServiceTest.failsAfterGeneratedCodeRetryLimitIsExhausted`; `UrlControllerTest.returnsServiceUnavailableWhenUniqueCodeCannotBeAllocated`; `RedirectIntegrationTest.generatedCodeCollisionRetriesAfterDatabaseConstraintFailure` | PASS |
| DTO response | `CreateUrlResponse` | `UrlControllerTest.returnsCreatedResponseAndLocation` | PASS |
| `createdAt` | `UrlMapping.createdAt`; `CreateUrlResponse.createdAt` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| `expiresAt` | `UrlMapping.expiresAt`; `CreateUrlResponse.expiresAt` | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |
| `shortUrl` | `UrlMapper.toCreateResponse` uses configured base URL | `UrlCreationServiceTest.createsCustomAliasAndBuildsResponse` | PASS |

## Redirect

| Requirement | Implementation | Test | Status |
| --- | --- | --- | --- |
| `GET /{shortCode}` | `RedirectController.redirect` | `RedirectControllerTest.returnsFoundWithDestinationLocation`; `RedirectIntegrationTest.createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics` | PASS |
| Short-code validation | `ShortCodeValidator`; `UrlRedirectService.resolve` | `UrlRedirectServiceTest.rejectsMalformedCodeBeforeAccessingInfrastructure`; `RedirectControllerTest.returnsBadRequestForMalformedCode` | PASS |
| Redis lookup | `RedisUrlCache.find`; `UrlRedirectService.resolve` | `RedisUrlCacheTest.readsExpiryAwareValueFromRedis`; `RedisUrlCacheTest.treatsRedisFailureAsCacheMiss`; `UrlRedirectServiceTest.returnsUnexpiredRedisValueWithoutQueryingPostgres` | PASS |
| PostgreSQL fallback | `UrlRedirectDatabaseResolver.resolveAndCache` | `UrlRedirectServiceTest.queriesPostgresAndPopulatesRedisOnCacheMiss`; `UrlRedirectDatabaseResolverTest.resolvesLockedMappingAndPopulatesCache`; `RedirectControllerTest.returnsServiceUnavailableWhenPrimaryDatabaseCannotBeReached`; `RedirectIntegrationTest.redisOutageFallsBackToPostgresForRedirect` | PASS |
| Expiration check | `CachedUrl.isExpiredAt`; `UrlMapping.isExpiredAt` | `UrlRedirectServiceTest.returnsGoneForExpiredCachedValue`; `UrlRedirectDatabaseResolverTest.rejectsExpiredMappingWithoutPopulatingCache`; `RedirectIntegrationTest.expiredMappingReturnsGone` | PASS |
| Cache population | `UrlRedirectDatabaseResolver.resolveAndCache`; `RedisUrlCache.put` | `UrlRedirectDatabaseResolverTest.resolvesLockedMappingAndPopulatesCache`; `RedirectIntegrationTest.createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics`; `RedirectIntegrationTest.malformedCachedValueFallsBackToPostgresAndRepairsCache` | PASS |
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

Latest local verification on August 13, 2026:

- `mvn --batch-mode --no-transfer-progress -Dapi.version=1.41 clean verify`
- 83 tests passed with no failures, errors, or skips.
- The result includes 69 unit, repository, and MockMvc tests plus all 14 PostgreSQL and Redis Testcontainers tests.
