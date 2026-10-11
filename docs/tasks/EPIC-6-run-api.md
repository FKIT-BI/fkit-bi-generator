# Epic 6 — Generator Run API/domain slice

Implemented the Generator-owned Run control API against Analytics release 013 tables. This slice covers validation, create/idempotency, cursor listing, detail with progress/checkpoint/failure/analytics context, stop, retry, immutable snapshot compilation, deterministic seed persistence, and reservation/conflict handling.

Contract evidence: `docs/contracts/generator-api-draft.yaml` (`listRuns`, `createRun`, `validateRun`, `getRun`, `stopRun`, `retryRun`) and the approved Epic 6/API/domain/run-execution specs in `fkit-bi-infra/docs/bmad/implementation-artifacts/`.

Implementation boundaries and choices:

- Equipment ownership remains Analytics; Generator reads the current equipment rows to compile the immutable snapshot and writes only Generator-owned run-control records. It does not add an Analytics HTTP dependency or a raw telemetry endpoint.
- Scheduled LOOP without an explicit `durationMs` or `eventTo` remains allowed. Its reservation is `[scheduledStartAt, infinity)` (nullable reservation end), so later overlapping reservations on the same machine conflict until release. This follows the approved OpenAPI/DTO/run-execution contract despite one contrary concurrency-rules sentence; no frozen spec was changed.
- Since release 013 has no separate request-duration column, the exact requested execution boundary fields (including `durationMs`) are retained in immutable `snapshot.execution`; normalized historical/scheduled bounds are also stored in `event_from`/`event_to`.
- Retry eligibility is enforced using the persisted FAILED/retryable state. Workers, engines, and state transitions that produce failures/checkpoints are intentionally outside this API task.

No Epic tests were run, as requested. Static review only; compilation was not attempted.
