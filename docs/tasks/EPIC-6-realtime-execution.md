# Epic 6 — Realtime execution

Реализован Realtime execution slice в Generator; Analytics, Web, deployment, Historical и Scheduled execution не затронуты.

Что сделано:

- `RealtimeRunDispatcher` опрашивает durable очередь и запускает до четырёх worker threads; отдельная bounded очередь отсутствует, поэтому Run не захватывается без доступного execution slot.
- `RealtimeRunLifecycleRepository` атомарно claim-ит `QUEUED`/просроченные leased `RUNNING`/`STOP_REQUESTED` Runs через `FOR UPDATE SKIP LOCKED`, сохраняет lease, lifecycle, попытки, counters и checkpoint state. Смена владельца lease блокирует запись устаревшего worker.
- `RealtimeRunExecutor` выполняет независимый tick schedule для каждого signal, выбирает только актуальный due tick и учитывает пропуски без catch-up burst. Формируется не более 1000 measurements на пакет; batch передаётся через `RealtimeTelemetryExecutionService` и имеющийся `TelemetrySink`.
- Batch envelope и его точный payload сохраняются до I/O в durable checkpoint JSON. Sequence и ID вида `<runId>:rt:<sequence>` повторяются после неопределённого результата/рестарта; после sink receipt прогресс и Run counters обновляются отдельно от транзакции raw writer.
- Behavior evaluator поддерживает типизированные `CONSTANT`, `UNIFORM`, `NORMAL`, `RAMP`, `SINE`, `COUNTER_RATE`, `PULSE`, `BERNOULLI`, `SEQUENCE`; sub-seed стабилен для Run/machine/signal/phase/cycle/tick, `seedOverride` поддержан.
- Sink теперь возвращает `TelemetryWriteResult`, так что commit/duplicate receipt учитывается как written, а исчерпанная временная realtime запись — как dropped; batch транзакция и writer остаются прежней границей записи.
- Stop прекращает новые ticks, дописывает/учитывает единственный durable pending batch, затем завершает Run и освобождает reservations. Terminal completion/failure также закрывает attempt и освобождает reservations; retryable failure сохраняет pending batch и тот же Run snapshot/seed.
- Retry привязывает новое realtime расписание к `generation_run_attempt.started_at`, установленному по DB clock при claim. Исторические `generation_run.started_at`, `event_from` и фиксированный `event_to` не переписываются; новые tick slots имеют ключ попытки, поэтому попытка начинает с текущего wall clock без catch-up прежних slots. Bounded end/progress остаются на исходном `event_to`; стабильный producer sequence и точный durable pending batch сохранены для идемпотентного replay при неопределённом write receipt.

Контрактные источники: `spec-generator-run-execution/{SPEC.md,execution-modes.md,progress-and-checkpoints.md,commands-and-recovery.md}`, `spec-generator-domain/signal-behavior-models.md`, `spec-raw-telemetry-ingestion/writer-protocol.md`, `generator-api-draft.yaml` и Epic 6 acceptance matrix.

Не выполнено в этом срезе: Historical/Scheduled workers, end-to-end raw→Analytics acceptance и Epic suite. Тесты не запускались. `./mvnw -B -DskipTests compile` также не стартовал: в окружении некорректен/не задан `JAVA_HOME`, `java` и `javac` отсутствуют в PATH. `git diff --check` прошёл.

Незакрытая спецификационная коллизия: execution companion допускает для immediate Realtime `eventTo`, но нормативный OpenAPI `RealtimeRunCreate` разрешает только optional `durationMs`, а DTO contract совпадает с OpenAPI. Реализация следует публичному OpenAPI и не добавляет `eventTo` в Realtime API.
