# ai-engineering-url-shortener

Production-oriented URL shortener built with Java 21, Spring Boot 3.5, PostgreSQL, Redis, Flyway, OpenAPI, Actuator, and Testcontainers.

## Architecture

The application is a single Spring Boot deployable organized into controller, service, repository, entity, DTO, exception, configuration, validation, and mapper layers under `com.sheshidhar.urlshortener`. PostgreSQL is the source of truth, Redis is a cache-aside optimization for redirects, and analytics events are persisted asynchronously on a bounded executor.

## Behavior

### Create a short URL

```http
POST /api/v1/urls
Content-Type: application/json

{
  "url": "https://example.com/products/123",
  "customAlias": "product123",
  "expiresAt": "2099-01-01T00:00:00Z"
}
```

`customAlias` and `expiresAt` are optional. A successful request returns `201 Created` with `shortCode`, `shortUrl`, `originalUrl`, `createdAt`, and `expiresAt`.

### Redirect

```http
GET /{shortCode}
```

| Situation | Status |
| --- | --- |
| Active URL | `302 Found` |
| Invalid short-code syntax | `400 Bad Request` |
| Unknown short code | `404 Not Found` |
| Previously created but expired URL | `410 Gone` |

Redis is checked before PostgreSQL. A database cache miss populates Redis, with TTL capped at the URL expiration time. Analytics is recorded asynchronously and cannot break a successful redirect.

### URL metadata

```http
GET /api/v1/urls/{shortCode}
```

Returns `shortCode`, `shortUrl`, `originalUrl`, `createdAt`, `expiresAt`, and `expired`. Metadata is read from PostgreSQL and does not populate the redirect cache or record a redirect event. A known expired URL returns `200 OK` with `expired: true`; an unknown code returns `404 Not Found`.

### Analytics

```http
GET /api/v1/urls/{shortCode}/analytics
```

Returns `shortCode`, `totalClickCount`, `createdAt`, and `lastAccessedAt`. A URL with no redirects has a count of zero and a null `lastAccessedAt`. Results are eventually consistent because redirect events are persisted asynchronously. Referrer, user-agent, and IP address are not collected to minimize personal data and retention obligations.

### Delete a short URL

```http
DELETE /api/v1/urls/{shortCode}
```

Returns `204 No Content` whether the URL existed or was already absent, making retries idempotent. Deletion evicts Redis before and after removing the PostgreSQL mapping and analytics events. A cache or database outage returns `503 Service Unavailable` rather than claiming a potentially incomplete deletion.

See the [API reference](docs/API.md) and [architecture decisions](docs/ARCHITECTURE.md) for endpoint details, trade-offs, and reliability boundaries.

## Run locally

Prerequisites: Docker Engine or Docker Desktop with Compose v2 support.

```bash
docker compose up --build
```

Then create and follow a link:

```bash
curl -i -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/products/123","customAlias":"product123"}'

curl -i http://localhost:8080/product123
```

Operational endpoints:

- Swagger UI: <http://localhost:8080/docs/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/internal/actuator/health>
- Liveness: <http://localhost:8080/internal/actuator/health/liveness>
- Readiness: <http://localhost:8080/internal/actuator/health/readiness>
- Metrics: <http://localhost:8080/internal/actuator/metrics>

Stop services with `docker compose down`. Add `--volumes` only when you intentionally want to delete local PostgreSQL and Redis data.

## Test

Prerequisites: Java 21 and Maven 3.9+. Docker is also required for the PostgreSQL and Redis Testcontainers integration suite.

```bash
mvn clean verify
```

Unit and MockMvc tests run without infrastructure. Testcontainers tests start real PostgreSQL and Redis when a compatible Docker daemon is available. CI uses Temurin Java 21 and runs the full suite.

Testcontainers can skip its integration class when Docker is unavailable or its API cannot be negotiated, even if Maven itself reports success. Confirm that the final result has zero skipped tests. Legacy Docker daemons limited to API 1.41 can use `mvn -Dapi.version=1.41 clean verify`; that compatibility command was used for the verified 83-test, zero-skip local run. CI separately asserts that the 14 container-backed tests executed.

## Configuration

Configuration is externalized through environment variables. Important defaults include:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/url_shortener` |
| `DB_USERNAME` | `url_shortener` |
| `DB_PASSWORD` | Required; Docker Compose supplies a local development value |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `BASE_URL` | `http://localhost:8080` |
| `SHORT_CODE_LENGTH` | `8` |
| `URL_CACHE_TTL` | `24h` |

Production deployments must set `DB_PASSWORD`, replace local development credentials, and set `BASE_URL` to the public HTTPS origin.

Copy `.env.example` when you need a local environment-variable template. Docker Compose supplies its own local service values, so copying the file is not required for `docker compose up --build`.

## Engineering scenarios

- **Greenfield:** decomposes the initial service into HTTP, business, persistence, validation, caching, and analytics concerns, followed by focused, integration, and runtime validation.
- **Brownfield:** evolves a PostgreSQL-only redirect into Redis cache-aside with expiry-aware TTL, PostgreSQL fallback, strict deletion invalidation, and concurrency-safe cache population.
- **Ambiguous requirement:** resolves what analytics counts, which metadata is retained, how failures affect redirects, and what consistency and retention assumptions apply before implementation.

The complete scenario narratives, risks, tests, and engineer decisions are in [docs/SCENARIOS.md](docs/SCENARIOS.md).

## Engineer-led AI usage

AI assisted with repository review and implementation options; the engineer supplied context and constraints, reviewed recommendations, made the final decision, and validated accepted work. The record includes a suggestion accepted for deletion/cache ordering, a transaction recommendation modified after real PostgreSQL behavior, and a root-page suggestion rejected as out-of-scope feature work. See [docs/AI_USAGE.md](docs/AI_USAGE.md).

## Key trade-offs

- PostgreSQL provides authoritative uniqueness and deletion correctness, but cache misses depend on database availability.
- Redis improves redirect latency and cache-hit availability, but requires expiry-aware values and carefully ordered invalidation.
- A pessimistic read lock closes the deletion/cache race, but cache-miss transactions can briefly delay deletion.
- Asynchronous analytics protects redirect reliability, but delivery is best effort and can undercount.

## Engineering documentation

- [API reference](docs/API.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Step 1 repository inspection](docs/REPOSITORY_INSPECTION.md)
- [Requirements traceability](docs/REQUIREMENTS_TRACEABILITY.md)
- [Three engineering scenarios](docs/SCENARIOS.md)
- [Trade-offs](docs/TRADEOFFS.md)
- [Security](docs/SECURITY.md)
- [Engineer-led AI usage](docs/AI_USAGE.md)

## Known limitations

The service has no authentication, ownership, rate limiting, or public-link abuse controls. Analytics is eventually consistent and best effort. Operational endpoints must be network-restricted in production. PostgreSQL driver socket/query deadlines are not globally configured, and creation currently classifies any database integrity violation as a code collision or alias conflict. These boundaries are intentional for the assessment and are detailed in the linked documentation.
