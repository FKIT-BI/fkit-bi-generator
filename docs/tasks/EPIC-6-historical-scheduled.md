# Epic 6 Historical and Scheduled execution task evidence

Implemented the Historical and Scheduled execution slice in the Generator module.

- `ScheduledRunStarter` claims due `SCHEDULED` records under row lock, rejects starts missed by more than 30 seconds, revalidates current enterprise/machine/line/signal readiness, rechecks reservations using actual start time, and queues the run only after those checks. No telemetry is published by the starter.
- `RealtimeRunLifecycleRepository` claims scheduled records through the existing realtime executor and historical records through a separate historical claim path. Historical chunk checkpoints and pending-batch envelopes are durable; batch IDs are derived from run, chunk start and batch ordinal for retry replay.
- `HistoricalRunExecutor` emits ordered, bounded batches in five-minute chunks, throttles writes, and records completed-through checkpoints and per-machine coverage. Each batch ACK advances durable counters and event-time progress; an explicit stop is observed after the active batch and preserves only the last fully completed chunk checkpoint/coverage. A retry replays from that chunk boundary with stable batch IDs. `HistoricalRunDispatcher` has a single worker and bounded queue; the database capacity slot and coverage exclusion remain the cross-process authority.
- Historical preflight in `RunRepository` checks every UTC day in the requested range for an ACTIVE registry row and an attached raw partition with the required signal/time index.
- Fixed an integration bug in `claimMode`: implicit sequence duration now applies only to `ONCE`; unbounded `LOOP` runs remain open-ended.

Verification: no Epic-wide tests were run, per task instruction. Static review and `git diff --check` are the remaining verification for this slice; Java compilation could not be run in the current environment because no Java runtime is installed.

No telemetry is emitted before scheduled start/revalidation.
