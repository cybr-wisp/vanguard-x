# Threat Model

## Scope

Vanguard is a simulated, unclassified portfolio project running locally
via Docker Compose. This threat model documents security decisions, not
operational hardening for a production deployment.

## Container security

- All containers run as non-root users
- Images use pinned versions (no `latest` tags)
- CycloneDX SBOM generated in CI for supply-chain visibility
- CodeQL static analysis runs on every push and weekly

## Network

- UDP ingestion port is bound to localhost only in development
- Spring Boot API does not expose management endpoints externally
- WebSocket connections accept all origins (development only;
  production would restrict to known frontends)

## Data

- No real sensor data, classified information, or PII is processed
- Ground-truth IDs are hidden from the tracking pipeline
- Redis TTLs ensure stale data self-expires
- Kafka topics use short retention for development

## Dependency management

- Dependabot monitors Maven and npm dependencies
- CycloneDX SBOM is generated per build for audit
- No runtime dependencies on external cloud services

## What would change for production

- TLS on all network paths (UDP/DTLS, Kafka SSL, Redis TLS, HTTPS)
- Authentication on REST and WebSocket endpoints
- Network segmentation between pipeline stages
- Secrets management (no hardcoded credentials)
- Container image scanning (Trivy, Snyk)
- Rate limiting on API endpoints
