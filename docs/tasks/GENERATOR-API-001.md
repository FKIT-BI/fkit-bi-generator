# GENERATOR-API-001 — contract design

## Scope

Define the public OpenAPI 3.1 boundary for durable Scenario configuration and realtime, historical and scheduled GenerationRun control.

## Evidence

- Contract: `docs/contracts/generator-api-draft.yaml`.
- Cross-service specification: `../fkit-bi-infra/docs/bmad/specs/spec-generator-api-contract/`.
- Domain specifications: `spec-generator-domain` and `spec-generator-run-execution` in the infra repository.
- Public API excludes equipment CRUD and raw telemetry ingestion.

## Verification

- YAML parsing.
- Unique operation IDs and exactly 12 public operations.
- OpenAPI lint before implementation handoff.
