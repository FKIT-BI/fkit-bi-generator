# Generator API contracts

- `generator-api-draft.yaml` — публичный OpenAPI 3.1 v1.0.0 управления Scenario и GenerationRun.
- Общий Auth API находится в `fkit-bi-infra/docs/contracts/auth-api.yaml`; Analytics API выпускает JWT, Generator только валидирует его.

Equipment CRUD и чтение производственной структуры принадлежат Analytics API. Raw telemetry передаётся внутренним `TelemetrySink` через JDBC и не имеет public REST endpoint.
