# URL Shortener API Reference

## Base URL

Local Docker environment:

```text
http://localhost:8080
```

Interactive OpenAPI documentation is available at:

```text
http://localhost:8080/docs/swagger-ui.html
```

The OpenAPI JSON specification is available at:

```text
http://localhost:8080/v3/api-docs
```

## Create a Short URL

```http
POST /api/v1/urls
Content-Type: application/json
```

### Request

```json
{
  "url": "https://example.com/products/123",
  "customAlias": "product123",
  "expiresAt": "2026-09-01T00:00:00Z"
}
```

| Field | Required | Description |
| --- | --- | --- |
| `url` | Yes | Absolute HTTP or HTTPS destination, up to 2,048 characters. |
| `customAlias` | No | Unique alias containing 4–32 letters, numbers, hyphens, or underscores. |
| `expiresAt` | No | Future expiration timestamp in ISO-8601 UTC format. |

If `customAlias` is omitted, the service generates a collision-resistant short code.

### Successful Response

Status: `201 Created`

```json
{
  "shortCode": "product123",
  "shortUrl": "http://localhost:8080/product123",
  "originalUrl": "https://example.com/products/123",
  "createdAt": "2026-08-12T18:00:00Z",
  "expiresAt": "2026-09-01T00:00:00Z"
}
```

The `Location` response header contains the new short URL.

### Responses

| Status | Meaning |
| --- | --- |
| `201 Created` | Short URL created. |
| `400 Bad Request` | JSON, URL, alias, or expiration validation failed. |
| `409 Conflict` | The requested custom alias already exists. |
| `503 Service Unavailable` | PostgreSQL is unavailable or a generated code could not be allocated. |

### Example

```bash
curl -i -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/products/123","customAlias":"product123"}'
```

## Follow a Short URL

```http
GET /{shortCode}
```

### Successful Response

Status: `302 Found`

The `Location` header contains the original destination URL.

### Responses

| Status | Meaning |
| --- | --- |
| `302 Found` | Redirect to the original URL. |
| `400 Bad Request` | The short-code syntax is invalid. |
| `404 Not Found` | The short code does not exist. |
| `410 Gone` | The short URL exists but has expired. |
| `503 Service Unavailable` | PostgreSQL is unavailable on a cache miss. |

### Example

```bash
curl -i http://localhost:8080/product123
```

Redis is used as a cache. On a cache miss or recoverable Redis failure, the service reads from PostgreSQL.

## Get URL Metadata

```http
GET /api/v1/urls/{shortCode}
```

### Successful Response

Status: `200 OK`

```json
{
  "shortCode": "product123",
  "shortUrl": "http://localhost:8080/product123",
  "originalUrl": "https://example.com/products/123",
  "createdAt": "2026-08-12T18:00:00Z",
  "expiresAt": "2026-09-01T00:00:00Z",
  "expired": false
}
```

An expired mapping remains available through this endpoint with `expired` set to `true`.

### Responses

| Status | Meaning |
| --- | --- |
| `200 OK` | Metadata returned. |
| `400 Bad Request` | The short-code syntax is invalid. |
| `404 Not Found` | The short code does not exist. |
| `503 Service Unavailable` | PostgreSQL is unavailable. |

## Get URL Analytics

```http
GET /api/v1/urls/{shortCode}/analytics
```

### Successful Response

Status: `200 OK`

```json
{
  "shortCode": "product123",
  "totalClickCount": 7,
  "createdAt": "2026-08-12T18:00:00Z",
  "lastAccessedAt": "2026-08-12T19:00:00Z"
}
```

`lastAccessedAt` is `null` before the first recorded redirect. Analytics are persisted asynchronously and are eventually consistent. Failed or expired redirects are not counted.

### Responses

| Status | Meaning |
| --- | --- |
| `200 OK` | Analytics returned. |
| `400 Bad Request` | The short-code syntax is invalid. |
| `404 Not Found` | The short code does not exist. |
| `503 Service Unavailable` | PostgreSQL is unavailable. |

## Delete a Short URL

```http
DELETE /api/v1/urls/{shortCode}
```

### Successful Response

Status: `204 No Content`

Deletion removes the URL mapping and its analytics and invalidates its Redis cache entry. The operation is idempotent, so an already absent URL also returns `204 No Content`.

### Responses

| Status | Meaning |
| --- | --- |
| `204 No Content` | Deletion completed or the URL was already absent. |
| `400 Bad Request` | The short-code syntax is invalid. |
| `503 Service Unavailable` | PostgreSQL is unavailable or Redis cannot be invalidated safely. |

### Example

```bash
curl -i -X DELETE http://localhost:8080/api/v1/urls/product123
```

## Error Format

API errors use the RFC 9457 problem-details structure with additional `code` and `timestamp` properties.

```json
{
  "type": "urn:problem:url-shortener:short-url-not-found",
  "title": "Short URL not found",
  "status": 404,
  "detail": "Short URL was not found: missing1",
  "code": "SHORT_URL_NOT_FOUND",
  "timestamp": "2026-08-12T18:00:00Z"
}
```

Validation failures also include a `fieldErrors` array:

```json
{
  "code": "VALIDATION_FAILED",
  "fieldErrors": [
    {
      "field": "customAlias",
      "message": "customAlias must contain between 4 and 32 characters"
    }
  ]
}
```

## Operational Endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /internal/actuator/health` | Aggregate application health. |
| `GET /internal/actuator/health/liveness` | Application liveness probe. |
| `GET /internal/actuator/health/readiness` | Application and PostgreSQL readiness probe. |
| `GET /internal/actuator/metrics` | Available application metrics. |

The service does not define a handler for `/`; opening only the base URL returns `404 Not Found`.
