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
