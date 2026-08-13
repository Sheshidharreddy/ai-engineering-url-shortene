# ai-engineering-url-shortener

Production-oriented URL shortener built with Java 21, Spring Boot 3, PostgreSQL, Redis, Flyway, OpenAPI, Actuator, and Testcontainers.

## Behavior

### Create a short URL

```http
POST /api/v1/urls
Content-Type: application/json

{
  "url": "https://example.com/products/123",
  "customAlias": "product123",
  "expiresAt": "2026-09-01T00:00:00Z"
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

Returns `204 No Content` whether the URL existed or was already absent, making retries idempotent. Deletion removes the PostgreSQL mapping and its analytics events, then invalidates the Redis redirect cache. A cache or database outage returns `503 Service Unavailable` rather than claiming a potentially incomplete deletion.

See [architecture decisions](docs/architecture.md) for the trade-offs and reliability boundaries.

## Run locally

Prerequisites: Docker with Compose support.

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
- Liveness: <http://localhost:8080/internal/actuator/health/liveness>
- Readiness: <http://localhost:8080/internal/actuator/health/readiness>
- Metrics: <http://localhost:8080/internal/actuator/metrics>

Stop services with `docker compose down`. Add `--volumes` only when you intentionally want to delete local PostgreSQL and Redis data.

## Test

```bash
mvn clean verify
```

Unit and MockMvc tests run without infrastructure. Testcontainers tests start real PostgreSQL and Redis when a compatible Docker daemon is available. CI uses Temurin Java 21 and runs the full suite.

## Configuration

Configuration is externalized through environment variables. Important defaults include:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/url_shortener` |
| `DB_USERNAME` | `url_shortener` |
| `DB_PASSWORD` | `url_shortener` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `BASE_URL` | `http://localhost:8080` |
| `SHORT_CODE_LENGTH` | `8` |
| `URL_CACHE_TTL` | `24h` |

Production deployments must replace default database credentials and set `BASE_URL` to the public HTTPS origin.
