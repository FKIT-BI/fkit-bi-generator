# Story 1.1 verification — Generator

- Spring Boot parent: 3.5.16; Java target remains 21. JDBC configuration and the existing `telemetry/JdbcTelemetrySink` were not changed.
- Added bounded `X-Trace-Id` ingress handling, common `ErrorResponse`, safe validation and unexpected-error responses, and process liveness/readiness probes.
- `docker run --rm -v fkit-bi-maven-cache:/root/.m2 -v "$PWD:/workspace" -w /workspace maven:3.9-eclipse-temurin-21 ./mvnw verify -q` — passed on 2026-10-10 (Java 21.0.12.1, Spring Boot 3.5.16; thirteen HTTP/application tests passed).
- HTTP coverage: valid/missing/invalid/128-character/oversized trace IDs, generated-ID equality between error header and body, malformed JSON, single- and multi-field validation, request-parameter validation, sanitized 500, 404/405 common errors, and independent liveness/readiness including refusal (503).
