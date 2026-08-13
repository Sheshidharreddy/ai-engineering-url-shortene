# AI Usage

AI was used as a reviewer and implementation assistant. The engineer retained responsibility for scope, design decisions, code review, and validation. The examples below describe work performed in this repository; they are not hypothetical interactions.

## Accepted suggestion: close the deletion/cache race

| Field | Record |
| --- | --- |
| Task | Prevent an in-flight cache miss from repopulating Redis after URL deletion. |
| Intent | Make deletion authoritative even when redirect and delete requests overlap. |
| Context supplied to AI | The cache-aside redirect flow, strict double eviction during deletion, PostgreSQL as source of truth, Redis key format, and the requirement that aliases may be reused. |
| Constraints | Preserve cache-hit availability, avoid adding brokers or distributed-lock infrastructure, and keep deletion idempotent. |
| AI recommendation | Acquire a PostgreSQL pessimistic read lock for cache-miss resolution, keep it through cache population, then let deletion acquire the conflicting write lock and perform the final eviction. |
| Outcome | Accepted. |
| Engineer decision | Accepted because it uses the authoritative database to order the two flows and leaves cache hits database-independent. |
| Rationale | A second eviction alone does not protect against a delayed writer that runs after deletion returns. Database lock ordering closes that window. |
| Validation performed | Added `RedirectIntegrationTest.concurrentCacheMissCannotRepopulateCacheAfterDeletion` and ran it against real PostgreSQL and Redis through Testcontainers. |

## Modified suggestion: transaction mode for locked reads

| Field | Record |
| --- | --- |
| Task | Define the transaction around the locked cache-miss lookup. |
| Intent | Hold the row lock until Redis population finishes. |
| Context supplied to AI | Spring Data JPA repository locking and the existing read-oriented resolver. |
| Constraints | The implementation must work on PostgreSQL rather than only with mocks. |
| AI recommendation | Initially use a read-only transaction because the method reads the mapping. |
| Outcome | Modified. |
| Engineer decision | Modified to a normal `@Transactional` boundary. |
| Rationale | PostgreSQL rejected `SELECT ... FOR SHARE` inside the read-only transaction used by the initial implementation. Locking is part of correctness and must be allowed by the transaction. |
| Validation performed | Re-ran the Testcontainers integration suite after removing `readOnly = true`; all 14 container-backed tests passed. |

## Rejected suggestion: add a root web page

| Field | Record |
| --- | --- |
| Task | Respond to the Whitelabel `404` shown for `GET /`. |
| Intent | Clarify whether the running application was broken. |
| Context supplied to AI | The application is a REST service whose redirect route requires a short code; Swagger UI is already configured. |
| Constraints | Do not add product functionality during the final hardening pass. |
| AI recommendation | A root controller or landing page was identified as one possible way to avoid the browser `404`. |
| Outcome | Rejected. |
| Engineer decision | Rejected. The root path is not part of the API contract; adding a page would be feature work. |
| Rationale | The `404` correctly means no route is mapped to `/`. API discoverability is handled with OpenAPI and `docs/API.md`. |
| Validation performed | Verified the documented Swagger UI, OpenAPI, API, redirect, and health routes instead of introducing a root endpoint. |

## Accepted and modified suggestion: AWS production platform

| Field | Record |
| --- | --- |
| Task | Address the production blockers for high availability, security perimeter, observability, and release safety. |
| Intent | Produce a deployable, repeatable production foundation without weakening the redirect reliability behavior already validated. |
| Context supplied to AI | The blocker list, the existing Spring Boot/PostgreSQL/Redis design, the strict Redis deletion behavior, and the engineer's explicit choices: AWS ECS, Terraform, and dev/UAT/prod. |
| Constraints | Keep `sheshidhar` naming, preserve PostgreSQL authority and Redis fallback semantics, do not invent an owned domain or AWS account, and do not claim cloud validation that was not executed. |
| AI recommendation | Use private multi-AZ ECS Fargate tasks behind ALB/ACM/WAF; Multi-AZ RDS; failover Redis with TLS; ADOT with Prometheus/X-Ray; GitHub OIDC releases; and DNS names `dev.go.<domain>`, `uat.go.<domain>`, and `go.<domain>`. |
| Outcome | Accepted with modifications. |
| Engineer decision | Accepted AWS ECS, Terraform, all three environments, and the DNS convention. Kept actual domain/account values configurable. Used rolling deployment with multiple rollback layers rather than adding an unnecessary canary controller. |
| Rationale | The topology removes single-AZ application, database, Redis, NAT, and deployment failure points while retaining one service and its existing consistency boundaries. Configurable ownership values prevent fabricated cloud assumptions. |
| Validation performed | Validated bootstrap and all environment roots with Terraform 1.15.8 and AWS provider 6.59.0; parsed both workflow files; ran the 88-test Maven suite with 14 real PostgreSQL/Redis integration tests and zero skips; proved the Prometheus endpoint in integration tests; validated Docker Compose; and built the production image. No AWS apply or remote GitHub run was claimed. |

## Accepted and modified suggestion: application hardening

| Field | Record |
| --- | --- |
| Task | Harden shutdown, database failure bounds, URL-creation retries, analytics durability, retention, and cold-cache behavior. |
| Intent | Remove known production reliability gaps without moving business logic into controllers or adding an unnecessary service boundary. |
| Context supplied to AI | The explicit hardening list, existing cache-aside and deletion locking flows, volatile analytics executor, Flyway schema, AWS ECS/Terraform target, and requirement to ask before unrelated major changes. |
| Constraints | Keep PostgreSQL authoritative, preserve successful redirects when analytics fails, support multiple ECS replicas, use database constraints for final correctness, and validate with real PostgreSQL/Redis where possible. |
| AI recommendation | Add optional idempotency keys with request fingerprints, classify named constraints, replace the volatile executor with a PostgreSQL outbox claimed through `SKIP LOCKED`, add bounded retention, coalesce cache misses per replica, set pgJDBC/query deadlines, and enforce Terraform connection budgets. |
| Outcome | Accepted with modifications. |
| Engineer decision | Accepted the application and schema changes. Kept the outbox in PostgreSQL instead of adding SQS/Kafka, and changed the Redis-restart load test to a deterministic cold-cache simulation after the older local Docker API made an in-class container restart destabilize unrelated tests. |
| Rationale | The selected design closes the reported crash-loss and retry gaps with existing dependencies. The test still verifies the post-restart database-load condition without falsely claiming that a live client reconnection test passed. |
| Validation performed | Ran 40 focused tests with zero failures, then iterated on the real PostgreSQL 17 and Redis 7.4 integration suite. The first real run caught and corrected a `CHAR`/`VARCHAR` schema mismatch; a later run exposed and corrected a dispatcher/test-cleanup race. Final `clean verify` passed all 107 tests, including 20 Testcontainers tests, with zero failures, errors, or skips. The Redis-restart case is explicitly a cold-cache simulation, not a live reconnection claim. |

## Working model

The repository follows this sequence for AI-assisted changes:

1. Supply requirements, current code, and constraints.
2. Review the proposed change for scope and correctness.
3. Accept, modify, or reject the recommendation explicitly.
4. Validate accepted code with focused tests, then broader integration and runtime checks.
