# Security

## Implemented controls

- Destination URLs must be absolute `http` or `https` URIs with a valid host.
- `javascript:`, `file:`, `data:`, malformed URIs, invalid ports, and embedded user credentials are rejected.
- Request validation limits destination URLs to 2,048 characters and aliases to 4–32 characters.
- Aliases accept only letters, numbers, hyphens, and underscores; infrastructure route names are reserved.
- PostgreSQL constraints enforce short-code format, expiration ordering, and uniqueness.
- Error responses use structured problem details without stack traces or internal exception text.
- Analytics stores no IP address, user-agent, referrer, or destination query data.
- Application secrets and local `.env` files are excluded by `.gitignore`; `.env.example` contains development placeholders only.
- Health details are hidden, and Redis is not treated as an authoritative health dependency.

## Operational requirements

- Replace local database credentials and set `BASE_URL` to the public HTTPS origin in every non-local environment.
- Store credentials in the deployment platform's secret manager; do not commit populated `.env` files.
- Restrict `/internal/actuator/**`, especially metrics, to trusted operational networks. These endpoints are intentionally unauthenticated inside the application because authentication is outside this assessment's scope.
- Apply gateway or load-balancer rate limits to creation, metadata, analytics, deletion, and redirect traffic.
- Define authorization before exposing management endpoints to untrusted users. The current assessment API has no authentication or ownership model.
- Add abuse, reputation, and phishing controls before offering public link creation.
- Configure database TLS, Redis network isolation/TLS where supported, backup policy, log retention, and dependency patching for production.

## Threat boundaries

The service stores destinations but does not fetch them, so a submitted private-network URL does not create server-side request forgery in the current implementation. Clients following redirects can still be sent to malicious destinations; public deployment therefore needs abuse controls outside this service.

Redirect responses intentionally expose the destination through the `Location` header. Application logs avoid logging destination URLs so query-string secrets are not copied into service logs. Analytics is operational/product telemetry rather than a security or billing ledger.

## Reporting

Do not disclose suspected vulnerabilities through public issues. Share them privately with the repository owner, including affected version, reproduction steps, impact, and any proposed mitigation.
