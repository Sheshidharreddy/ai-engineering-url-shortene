# Step 1 Repository Inspection

This document records the repository inspection required by `Project_requirement.md` Step 1. It reflects the implementation after the final layered-package refactor and focused coverage fixes.

## Architecture summary

The repository contains one Java 21 Spring Boot application organized into controller, service, repository, entity, DTO, exception, configuration, validator, and mapper layers under `com.sheshidhar.urlshortener`. PostgreSQL is the source of truth and enforces short-code uniqueness. Redis is an expiry-aware cache-aside optimization for redirects. Redirect analytics is best effort and runs asynchronously on a bounded executor. Flyway owns the schema, Actuator exposes operational health, Docker Compose runs the complete local stack, and GitHub Actions runs tests and builds the runtime image.

## Artifact coverage

| Step 1 review item | Repository evidence | Status |
| --- | --- | --- |
| `pom.xml` | Java 21, Spring Boot, JPA, Redis, validation, Actuator, Flyway, OpenAPI, Testcontainers, and JaCoCo configuration | PASS |
| Application configuration | `src/main/resources/application.yml`; validated configuration property records; `.env.example` | PASS |
| Java source code | Layered source tree under `src/main/java/com/sheshidhar/urlshortener` | PASS |
| Controllers | `UrlController`; `RedirectController` | PASS |
| Services | Creation, redirect, metadata, analytics, deletion, caching, and code-generation services | PASS |
| Repositories | Mapping and event repositories plus isolated transactional writers | PASS |
| Entities | `UrlMapping`; `RedirectEvent` | PASS |
| DTOs | Separate request, response, metadata, analytics, and cached-value records | PASS |
| Exception handling | Domain exceptions and RFC 9457-style `ProblemDetail` responses in `ApiExceptionHandler` | PASS |
| Redis integration | `RedisUrlCache`; cache interfaces; externalized Redis configuration | PASS |
| Flyway migrations | `V1__create_url_shortener_schema.sql` with constraints and indexes | PASS |
| Tests | Unit, MockMvc, repository, and real PostgreSQL/Redis Testcontainers tests | PASS |
| `Dockerfile` | Multi-stage Java 21 build, non-root runtime, readiness health check | PASS |
| `docker-compose.yml` | Application, PostgreSQL, Redis, health checks, dependencies, and persistent volumes | PASS |
| GitHub Actions | Java 21 verification, integration-test execution assertion, and image build | PASS |
| `README.md` | Architecture, behavior, setup, test, configuration, documentation, and limitations | PASS |
| `docs/` | API, architecture, traceability, scenarios, trade-offs, security, and AI usage | PASS |
| `.gitignore` | Excludes secrets, IDE metadata, logs, build output, and OS metadata | PASS |

## Primary control flows

| Flow | Implementation path | Result |
| --- | --- | --- |
| 1. URL creation | `UrlController.create` → `UrlCreationService` → destination validation → custom alias or generated code → transactional database writer → `UrlMapper` | Returns `201 Created`; the database uniqueness constraint remains authoritative. |
| 2. Redirect | `RedirectController.redirect` → `UrlRedirectService.resolve` | Returns `302 Found` with the validated destination in `Location`. |
| 3. Redis cache hit | `RedisUrlCache.find` → cached expiration check → analytics submission | Returns without a PostgreSQL mapping lookup. |
| 4. Redis cache miss | Cache miss/failure → `UrlRedirectDatabaseResolver` → locked PostgreSQL lookup → expiration check → Redis population | PostgreSQL remains authoritative; active mappings populate Redis. |
| 5. URL expiration | `@Future` and service validation on creation; `CachedUrl.isExpiredAt` and `UrlMapping.isExpiredAt` on reads | Expired redirects return `410`; metadata returns `200` with `expired: true`. |
| 6. Analytics | Successful resolution → `AsyncRedirectAnalyticsRecorder` → `RedirectAnalyticsWriter` → aggregate query | Best-effort events cannot break redirects; reads are eventually consistent. |
| 7. Delete | `UrlController.delete` → `UrlDeletionService` → `UrlDeletionWriter` | Idempotently removes events and mapping and returns `204`. |
| 8. Cache invalidation | Redis eviction before deletion → committed database delete → final eviction; cache misses hold a database read lock through population | Prevents stale repopulation after deletion, including alias reuse. |
| 9. Error handling | Domain, validation, malformed-body, database, and cache exceptions → `ApiExceptionHandler` | Returns consistent problem details with appropriate `400`, `404`, `409`, `410`, or `503` status. |

## Findings

### Critical

None.

### High

- Resolved: corrected the compile-breaking `writer.sayve(...)` typo in `AsyncRedirectAnalyticsRecorder` to call `RedirectAnalyticsWriter.save(...)`.

### Medium

- Resolved: corrected Redis test-coverage wording in `docs/SCENARIOS.md`.
- Resolved: added focused tests for malformed JSON requests, malformed cached values, generated-code retry exhaustion, non-future expiration, and generated-code format.
- Resolved: added a full redirect-flow Redis outage integration test that verifies PostgreSQL fallback and Redis recovery.
- Resolved: added full-flow malformed-cache repair and HTTP `503` coverage for exhausted generated-code retries.

### Low

- `Project_requirement.md` remains untracked until the engineer includes it in a commit.
- Ignored local `.DS_Store`, `.idea`, and `target` artifacts exist but are not tracked.
- Invalid cross-field configuration combinations rely on application startup failure rather than dedicated relational property validation.

No major production change was required by this inspection.

## Validation

`mvn --batch-mode --no-transfer-progress -Dapi.version=1.41 clean verify` completed successfully with 83 tests, zero failures, zero errors, and zero skips. The run included 69 fast tests and all 14 real PostgreSQL and Redis Testcontainers tests.

`docker compose config --quiet` passed, and `docker compose up -d --build --force-recreate` rebuilt the Java 21 image and recreated the stack independently of the IDE while preserving named data volumes. PostgreSQL 17 accepted connections, Redis returned `PONG`, Flyway history reported migration version 1 as successful, and the application container became healthy. Both health and readiness returned `UP`; the README create and redirect flow returned HTTP `201` and `302`, followed by successful cleanup through the delete endpoint.

The complete runtime smoke test created generated code `7WLmYib7` for `https://example.com`, observed two HTTP `302` redirects, read metadata with HTTP `200`, and observed analytics count `2` with non-null `lastAccessedAt`. A separately created expiring URL returned HTTP `410` with `SHORT_URL_EXPIRED`. The primary Redis key changed from present to absent across HTTP `204` deletion, and its next redirect returned HTTP `404`. `/actuator/health` correctly returned `404` because the configured management base path is `/internal/actuator`; health, liveness, and readiness under that path each returned HTTP `200` with `UP`. Temporary smoke-test records were deleted.
