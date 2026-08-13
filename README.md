# ai-engineering-url-shortener

Production-oriented URL shortener built with Java 21, Spring Boot 3.5, PostgreSQL, Redis, Flyway, OpenAPI, Actuator, and Testcontainers.

Production infrastructure targets AWS ECS Fargate through Terraform, with isolated dev, UAT, and production environments.

## Architecture

The application is a single Spring Boot deployable organized into controller, service, repository, entity, DTO, exception, configuration, validation, and mapper layers under `com.sheshidhar.urlshortener`. PostgreSQL is the source of truth, Redis is a cache-aside optimization for redirects, and analytics uses a durable PostgreSQL outbox with eventually consistent event aggregation.

## Behavior

### Create a short URL

```http
POST /api/v1/urls
Content-Type: application/json
Idempotency-Key: create-product-123

{
  "url": "https://example.com/products/123",
  "customAlias": "product123",
  "expiresAt": "2099-01-01T00:00:00Z"
}
```

`customAlias`, `expiresAt`, and the `Idempotency-Key` header are optional. Retrying the same normalized request with the same 8–128 character key returns the original mapping; reusing a key for a different request returns `409 Conflict`. A successful request returns `201 Created` with `shortCode`, `shortUrl`, `originalUrl`, `createdAt`, and `expiresAt`.

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

Redis is checked before PostgreSQL. A database cache miss populates Redis, with TTL capped at the URL expiration time. Concurrent misses for one code are coalesced to one database load per application replica. Successful resolution attempts to durably enqueue analytics, but enqueue failure is isolated and cannot change the redirect response.

### URL metadata

```http
GET /api/v1/urls/{shortCode}
```

Returns `shortCode`, `shortUrl`, `originalUrl`, `createdAt`, `expiresAt`, and `expired`. Metadata is read from PostgreSQL and does not populate the redirect cache or record a redirect event. A known expired URL returns `200 OK` with `expired: true`; an unknown code returns `404 Not Found`.

### Analytics

```http
GET /api/v1/urls/{shortCode}/analytics
```

Returns `shortCode`, `totalClickCount`, `createdAt`, and `lastAccessedAt`. A URL with no redirects has a count of zero and a null `lastAccessedAt`. Results are eventually consistent while durable outbox rows are dispatched. Raw events are retained for 90 days by default, so counts cover the configured retention window. Referrer, user-agent, and IP address are not collected.

### Delete a short URL

```http
DELETE /api/v1/urls/{shortCode}
```

Returns `204 No Content` whether the URL existed or was already absent, making retries idempotent. Deletion evicts Redis before and after removing the PostgreSQL mapping and analytics events. A cache or database outage returns `503 Service Unavailable` rather than claiming a potentially incomplete deletion.

See the [API reference](docs/API.md) and [architecture decisions](docs/ARCHITECTURE.md) for endpoint details, trade-offs, and reliability boundaries.

## Run locally

Choose one of the following ways to run the application. Do not run the Compose
`app` service and a local Java process at the same time because both use port
`8080`.

### Option 1: Run everything from the terminal with Docker Compose

Prerequisite: Docker Engine or Docker Desktop with Compose v2 support.

```bash
docker compose up --build
```

This starts PostgreSQL, Redis, and the application. Press `Ctrl+C` to stop them,
then run `docker compose down` to remove the containers and network.

### Option 2: Run the application from the terminal with Maven

Prerequisites: Java 21, Maven 3.9+, and Docker Engine or Docker Desktop.

Start only PostgreSQL and Redis in Docker:

```bash
docker compose up -d postgres redis
```

Then start Spring Boot from the project root:

```bash
export DB_PASSWORD=url_shortener
export BASE_URL=http://localhost:8080
mvn spring-boot:run
```

Press `Ctrl+C` to stop the application. Run `docker compose down` when you no
longer need PostgreSQL and Redis.

### Option 3: Run the application from IntelliJ IDEA

Prerequisites: IntelliJ IDEA, a Java 21 JDK, and Docker Engine or Docker Desktop.
IntelliJ can use its bundled Maven installation.

1. From a terminal in the project root, start only the dependencies:

   ```bash
   docker compose up -d postgres redis
   ```

2. Open the project directory in IntelliJ IDEA and import `pom.xml` as a Maven
   project.
3. Set the project SDK to Java 21 and allow Maven dependencies to finish
   loading.
4. Open `UrlShortenerApplication.java` and create or edit its Spring Boot run
   configuration.
5. Add these environment variables to the run configuration:

   ```text
   DB_PASSWORD=url_shortener
   BASE_URL=http://localhost:8080
   ```

6. Run `UrlShortenerApplication`. The application is ready when the IntelliJ
   console reports that it started on port `8080`.

If the Compose `app` service is already running, stop it before starting from
IntelliJ or Maven:

```bash
docker compose stop app
```

### Verify the running application

The following commands work regardless of which run option you selected. A
timestamp keeps the test alias unique across repeated runs:

```bash
export BASE_URL=http://localhost:8080
export TEST_ALIAS="product-$(date +%s)"

curl -i -X POST "${BASE_URL}/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"https://example.com/products/123\",\"customAlias\":\"${TEST_ALIAS}\"}"

curl -i "${BASE_URL}/${TEST_ALIAS}"
```

The create request should return `201 Created`, and the redirect request should
return `302 Found`.

The management API is intentionally unauthenticated for local development. When `MANAGEMENT_API_KEY` is configured, every `/api/v1/urls/**` request must include `X-API-Key`; public redirects remain unauthenticated.

Operational endpoints:

- Swagger UI: <http://localhost:8080/docs/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/internal/actuator/health>
- Liveness: <http://localhost:8080/internal/actuator/health/liveness>
- Readiness: <http://localhost:8080/internal/actuator/health/readiness>
- Metrics: <http://localhost:8080/internal/actuator/metrics>
- Prometheus: <http://localhost:8080/internal/actuator/prometheus>

Add `--volumes` to `docker compose down` only when you intentionally want to
delete all local PostgreSQL and Redis data.

## Test

Prerequisites: Java 21 and Maven 3.9+. Docker is also required for the PostgreSQL and Redis Testcontainers integration suite.

```bash
mvn clean verify
```

Unit and MockMvc tests run without infrastructure. Testcontainers tests start real PostgreSQL and Redis when a compatible Docker daemon is available. CI uses Temurin Java 21 and runs the full suite.

Testcontainers can skip its integration class when Docker is unavailable or its API cannot be negotiated, even if Maven itself reports success. Confirm that the final result has zero skipped tests. Legacy Docker daemons limited to API 1.41 can use `mvn -Dapi.version=1.41 clean verify`. CI separately asserts that the container-backed integration tests executed.

## Configuration

Configuration is externalized through environment variables. Important defaults include:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/url_shortener` |
| `DB_USERNAME` | `url_shortener` |
| `DB_PASSWORD` | Required; Docker Compose supplies a local development value |
| `DB_POOL_SIZE` | `10` connections per replica |
| `DB_CONNECT_TIMEOUT_SECONDS` | `3` |
| `DB_SOCKET_TIMEOUT_SECONDS` | `10` |
| `DB_QUERY_TIMEOUT_MS` | `5000` |
| `DB_LOCK_TIMEOUT_MS` | `3000` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `REDIS_PASSWORD` | Empty locally; required by Terraform-managed Redis |
| `REDIS_SSL` | `false` locally; `true` on AWS |
| `BASE_URL` | `http://localhost:8080` |
| `MANAGEMENT_API_KEY` | Empty locally; generated in AWS Secrets Manager |
| `SHORT_CODE_LENGTH` | `8` |
| `URL_CACHE_TTL` | `24h` |
| `ANALYTICS_DISPATCH_BATCH_SIZE` | `100` |
| `ANALYTICS_RETENTION` | `90d` |
| `SHUTDOWN_TIMEOUT` | `45s` |

Production deployments must set `DB_PASSWORD`, replace local development credentials, and set `BASE_URL` to the public HTTPS origin. Terraform defines each replica's pool size and rejects a plan when the maximum replica count would exceed the environment's application connection budget.

## Deploy to AWS

Terraform definitions under `infra/` provide private multi-AZ ECS Fargate services, ALB/ACM/WAF, Multi-AZ PostgreSQL, failover Redis with TLS, Secrets Manager, backups, autoscaling, CloudWatch, X-Ray, Prometheus, alarms, and rolling rollback-safe releases.

Suggested DNS names are `dev.go.<owned-domain>`, `uat.go.<owned-domain>`, and `go.<owned-domain>`. The owned Route53 domain, AWS account, region, state bucket, alert destination, and GitHub environment approvals must be supplied by the operator.

See [AWS infrastructure](infra/README.md) for bootstrap/deployment steps and [production operations](docs/OPERATIONS.md) for SLOs, alerting, rollback, restore, failover, rotation, and on-call procedures.

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
- A PostgreSQL outbox survives application crashes after enqueue and supports competing dispatchers, but an enqueue failure can still undercount and is intentionally isolated from redirects.

## Engineering documentation

- [API reference](docs/API.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Step 1 repository inspection](docs/REPOSITORY_INSPECTION.md)
- [Requirements traceability](docs/REQUIREMENTS_TRACEABILITY.md)
- [Three engineering scenarios](docs/SCENARIOS.md)
- [Trade-offs](docs/TRADEOFFS.md)
- [Security](docs/SECURITY.md)
- [Production operations](docs/OPERATIONS.md)
- [Engineer-led AI usage](docs/AI_USAGE.md)

## Known limitations

The AWS deployment uses one environment-level API key rather than user identity or per-link ownership. WAF provides gateway rate limiting and AWS-managed IP/input protections, but destination phishing classification still requires an external reputation provider and an abuse-response process. Analytics can undercount when PostgreSQL rejects the initial outbox enqueue, and retention intentionally removes raw events after the configured period. The platform is multi-AZ, not multi-region, and backup restorability must be proven through scheduled drills.
