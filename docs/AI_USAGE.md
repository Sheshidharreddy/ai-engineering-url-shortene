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
| Engineer decision | Modified to a normal `@Transactional` boundary. |
| Rationale | PostgreSQL rejected `SELECT ... FOR SHARE` inside the read-only transaction used by the initial implementation. Locking is part of correctness and must be allowed by the transaction. |
| Validation performed | Re-ran the Testcontainers integration suite after removing `readOnly = true`; all container-backed tests passed. |

## Rejected suggestion: add a root web page

| Field | Record |
| --- | --- |
| Task | Respond to the Whitelabel `404` shown for `GET /`. |
| Intent | Clarify whether the running application was broken. |
| Context supplied to AI | The application is a REST service whose redirect route requires a short code; Swagger UI is already configured. |
| Constraints | Do not add product functionality during the final hardening pass. |
| AI recommendation | A root controller or landing page was identified as one possible way to avoid the browser `404`. |
| Engineer decision | Rejected. The root path is not part of the API contract; adding a page would be feature work. |
| Rationale | The `404` correctly means no route is mapped to `/`. API discoverability is handled with OpenAPI and `docs/API.md`. |
| Validation performed | Verified the documented Swagger UI, OpenAPI, API, redirect, and health routes instead of introducing a root endpoint. |

## Working model

The repository follows this sequence for AI-assisted changes:

1. Supply requirements, current code, and constraints.
2. Review the proposed change for scope and correctness.
3. Accept, modify, or reject the recommendation explicitly.
4. Validate accepted code with focused tests, then broader integration and runtime checks.
