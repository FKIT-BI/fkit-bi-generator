package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.fkit.bi.generator.run.RunModels.*;
import ru.fkit.bi.generator.scenario.ScenarioModels.MachineTarget;

@Repository
public class RunRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private static final RowMapper<RunRow> ROW_MAPPER = (rs, n) -> new RunRow(
      rs.getObject("id", UUID.class), rs.getObject("scenario_id", UUID.class),
      rs.getObject("enterprise_id", UUID.class), Mode.valueOf(rs.getString("mode")),
      Status.valueOf(rs.getString("status")), rs.getInt("attempt_no"), rs.getLong("random_seed"),
      instant(rs.getTimestamp("requested_at")), instant(rs.getTimestamp("scheduled_start_at")),
      instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
      instant(rs.getTimestamp("stop_requested_at")), instant(rs.getTimestamp("event_from")),
      instant(rs.getTimestamp("event_to")), rs.getString("current_phase_code"), rs.getLong("cycle_number"),
      instant(rs.getTimestamp("generated_through")), rs.getLong("generated_measurements"),
      rs.getLong("written_measurements"), rs.getLong("dropped_measurements"), rs.getLong("failed_batches"),
      instant(rs.getTimestamp("last_successful_write_at")), rs.getString("failure_code"),
      rs.getString("failure_message"), instant(rs.getTimestamp("failure_at")),
      (Boolean) rs.getObject("failure_retryable"), parseSnapshot(rs.getString("snapshot")));

  public RunRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  public void lockIdempotencyKey(String principalId, String key) {
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
        "fkit-bi:run-idempotency:" + principalId + ":" + key);
  }

  public boolean tryLockMachine(UUID machineId) {
    Boolean acquired = jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class, machineId.toString());
    return Boolean.TRUE.equals(acquired);
  }

  public boolean tryLockHistoricalCapacity() {
    Boolean acquired = jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock(hashtextextended('fkit-bi:historical-capacity',0))", Boolean.class);
    return Boolean.TRUE.equals(acquired);
  }

  public Optional<Idempotency> getIdempotency(String principalId, String key) {
    List<Idempotency> rows = jdbc.query(
        "SELECT request_hash,run_id FROM generator.generation_run_idempotency WHERE principal_id=? AND idempotency_key=?",
        (rs, n) -> new Idempotency(rs.getBytes("request_hash"), rs.getObject("run_id", UUID.class)), principalId, key);
    return rows.stream().findFirst();
  }

  public List<Conflict> reservationConflicts(List<UUID> machineIds, Instant from, Instant to) {
    if (machineIds.isEmpty()) return List.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(machineIds.size(), "?"));
    List<Object> args = new ArrayList<>(machineIds);
    args.add(Timestamp.from(from));
    args.add(to == null ? null : Timestamp.from(to));
    String sql = "SELECT machine_id,run_id,reservation_kind,reserved_from,reserved_to FROM generator.generation_run_reservation "
        + "WHERE released_at IS NULL AND machine_id IN (" + placeholders + ") "
        + "AND tstzrange(reserved_from,reserved_to,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)') "
        + "ORDER BY machine_id,run_id";
    return jdbc.query(sql, (rs, n) -> new Conflict("RUN_RESERVATION_OVERLAP",
        List.of(rs.getObject("machine_id", UUID.class)), instant(rs.getTimestamp("reserved_from")),
        instant(rs.getTimestamp("reserved_to")), rs.getObject("run_id", UUID.class)), args.toArray());
  }

  public List<Conflict> coverageConflicts(List<UUID> machineIds, Instant from, Instant to) {
    if (machineIds.isEmpty() || from == null || to == null) return List.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(machineIds.size(), "?"));
    List<Object> args = new ArrayList<>(machineIds);
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(to));
    return jdbc.query("SELECT machine_id,run_id,event_from,event_to FROM generator.historical_coverage "
            + "WHERE machine_id IN (" + placeholders + ") AND tstzrange(event_from,event_to,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)') "
            + "ORDER BY machine_id,event_from",
        (rs, n) -> new Conflict("HISTORICAL_RANGE_OVERLAP", List.of(rs.getObject("machine_id", UUID.class)),
            instant(rs.getTimestamp("event_from")), instant(rs.getTimestamp("event_to")), rs.getObject("run_id", UUID.class)), args.toArray());
  }

  public Optional<UUID> historicalCapacityOwner() {
    List<UUID> rows = jdbc.query("SELECT run_id FROM generator.historical_capacity_slot WHERE slot_key=1",
        (rs, n) -> rs.getObject(1, UUID.class));
    return rows.stream().findFirst();
  }

  public List<Instant> missingHistoricalPartitions(Instant from, Instant to) {
    return jdbc.query("SELECT d.day::date FROM generate_series("
            + "((?::timestamptz AT TIME ZONE 'UTC')::date)::timestamp, "
            + "(((?::timestamptz - interval '1 millisecond') AT TIME ZONE 'UTC')::date)::timestamp, interval '1 day') AS d(day) "
            + "LEFT JOIN telemetry.raw_partition_registry r ON r.partition_day=d.day::date "
            + "WHERE r.status IS DISTINCT FROM 'ACTIVE' OR NOT EXISTS ("
            + "SELECT 1 FROM pg_inherits table_partition "
            + "JOIN pg_inherits index_partition ON index_partition.inhparent='telemetry.ix_raw_measurement_signal_time'::regclass "
            + "JOIN pg_index child_index ON child_index.indexrelid=index_partition.inhrelid "
            + "AND child_index.indrelid=table_partition.inhrelid "
            + "WHERE table_partition.inhparent='telemetry.raw_measurement'::regclass "
            + "AND table_partition.inhrelid=to_regclass('telemetry.raw_measurement_'||to_char(d.day::date,'YYYYMMDD'))"
            + ") ORDER BY d.day",
        (rs, n) -> rs.getDate(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        Timestamp.from(from), Timestamp.from(to));
  }

  public boolean lockScenario(UUID id) {
    return !jdbc.query("SELECT id FROM generator.generation_scenario WHERE id=? FOR SHARE",
        (rs, n) -> rs.getObject(1, UUID.class), id).isEmpty();
  }

  @Transactional
  public void create(String principalId, String idempotencyKey, byte[] requestHash, CreateRequest request,
      ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario, JsonNode snapshot, long seed, Instant eventFrom, Instant eventTo,
      Instant reservedFrom, Instant reservedTo, Instant requestedAt) {
    UUID runId = UUID.randomUUID();
    Status status = request.mode() == Mode.SCHEDULED ? Status.SCHEDULED : Status.QUEUED;
    jdbc.update("INSERT INTO generator.generation_run(id,scenario_id,enterprise_id,mode,status,snapshot,random_seed,"
            + "event_from,event_to,scheduled_start_at,requested_at,attempt_no,created_at,updated_at) "
            + "VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,1,?,?)",
        runId, scenario.id(), scenario.enterpriseId(), request.mode().name(), status.name(), json(snapshot), seed,
        timestamp(eventFrom), timestamp(eventTo), timestamp(request.scheduledStartAt()), timestamp(requestedAt),
        timestamp(requestedAt), timestamp(requestedAt));
    for (MachineTarget target : scenario.machineTargets()) {
      jdbc.update("INSERT INTO generator.generation_run_reservation(enterprise_id,run_id,machine_id,reservation_kind,"
              + "reserved_from,reserved_to,created_at) VALUES (?,?,?,?,?,?,?)",
          scenario.enterpriseId(), runId, target.machineId(), request.mode().name(), timestamp(reservedFrom),
          timestamp(reservedTo), timestamp(requestedAt));
    }
    if (request.mode() == Mode.HISTORICAL) {
      jdbc.update("INSERT INTO generator.historical_capacity_slot(slot_key,run_id,acquired_at) VALUES (1,?,?)",
          runId, timestamp(requestedAt));
    }
    jdbc.update("INSERT INTO generator.generation_run_attempt(run_id,attempt_no,created_at) VALUES (?,1,?)",
        runId, timestamp(requestedAt));
    jdbc.update("INSERT INTO generator.generation_run_idempotency(principal_id,idempotency_key,request_hash,run_id,created_at) VALUES (?,?,?,?,?)",
        principalId, idempotencyKey, requestHash, runId, timestamp(requestedAt));
  }

  public Optional<Detail> get(UUID runId) {
    List<RunRow> rows = jdbc.query("SELECT * FROM generator.generation_run WHERE id=?", ROW_MAPPER, runId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(detail(rows.getFirst()));
  }

  public List<Summary> list(UUID enterpriseId, UUID scenarioId, UUID machineId, Mode mode,
      List<Status> statuses, Instant requestedFrom, Instant requestedTo, Cursor cursor, int limit) {
    StringBuilder sql = new StringBuilder("SELECT r.*, count(DISTINCT mr.machine_id)::integer AS machine_count "
        + "FROM generator.generation_run r LEFT JOIN generator.generation_run_reservation mr ON mr.run_id=r.id "
        + "WHERE r.enterprise_id=?");
    List<Object> args = new ArrayList<>();
    args.add(enterpriseId);
    if (scenarioId != null) { sql.append(" AND r.scenario_id=?"); args.add(scenarioId); }
    if (machineId != null) { sql.append(" AND EXISTS (SELECT 1 FROM generator.generation_run_reservation fm WHERE fm.run_id=r.id AND fm.machine_id=?)"); args.add(machineId); }
    if (mode != null) { sql.append(" AND r.mode=?"); args.add(mode.name()); }
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND r.status IN (").append(String.join(",", java.util.Collections.nCopies(statuses.size(), "?"))).append(')');
      statuses.forEach(s -> args.add(s.name()));
    }
    if (requestedFrom != null) { sql.append(" AND r.requested_at>=?"); args.add(timestamp(requestedFrom)); }
    if (requestedTo != null) { sql.append(" AND r.requested_at<?"); args.add(timestamp(requestedTo)); }
    if (cursor != null) {
      sql.append(" AND (r.requested_at<? OR (r.requested_at=? AND r.id<?))");
      args.add(timestamp(cursor.requestedAt())); args.add(timestamp(cursor.requestedAt())); args.add(cursor.id());
    }
    sql.append(" GROUP BY r.id ORDER BY r.requested_at DESC,r.id DESC LIMIT ?");
    args.add(limit + 1);
    return jdbc.query(sql.toString(), (rs, n) -> summary(ROW_MAPPER.mapRow(rs, n), rs.getInt("machine_count")), args.toArray());
  }

  @Transactional
  public Status stop(UUID runId, Instant now) {
    RunRow row = getForUpdate(runId).orElseThrow(() -> new RunNotFoundException());
    if (row.status() == Status.SCHEDULED || row.status() == Status.QUEUED) {
      jdbc.update("UPDATE generator.generation_run SET status='CANCELLED',finished_at=?,updated_at=?,lease_owner=NULL,lease_until=NULL WHERE id=?",
          timestamp(now), timestamp(now), runId);
      jdbc.update("UPDATE generator.generation_run_attempt SET finished_at=COALESCE(finished_at,?) WHERE run_id=? AND attempt_no=?",
          timestamp(now),runId,row.attemptNo());
      releaseReservations(runId, now);
      jdbc.update("DELETE FROM generator.historical_capacity_slot WHERE run_id=?", runId);
      return Status.CANCELLED;
    }
    if (row.status() == Status.RUNNING) {
      jdbc.update("UPDATE generator.generation_run SET status='STOP_REQUESTED',stop_requested_at=?,updated_at=? WHERE id=?",
          timestamp(now), timestamp(now), runId);
      return Status.STOP_REQUESTED;
    }
    return row.status();
  }

  @Transactional
  public int retry(UUID runId, Instant now) {
    RunRow row = getForUpdate(runId).orElseThrow(() -> new RunNotFoundException());
    if (row.status() != Status.FAILED || !Boolean.TRUE.equals(row.failureRetryable())) return -1;
    List<UUID> machineIds = machineIds(runId);
    for (UUID machineId : machineIds) {
      if (!tryLockMachine(machineId)) return -2;
    }
    Instant reservedFrom = row.mode() == Mode.HISTORICAL ? checkpoint(runId)
        .map(value -> value.completedThrough() == null ? row.eventFrom() : value.completedThrough()).orElse(row.eventFrom())
        : row.scheduledStartAt() == null ? row.requestedAt() : row.scheduledStartAt();
    Instant reservedTo = row.eventTo();
    List<Conflict> conflicts = reservationConflicts(machineIds, reservedFrom, reservedTo).stream()
        .filter(c -> !runId.equals(c.conflictingRunId())).toList();
    if (!conflicts.isEmpty()) return -3;
    if (row.mode() == Mode.HISTORICAL) {
      if (!tryLockHistoricalCapacity()) return -4;
      Optional<UUID> owner = historicalCapacityOwner();
      if (owner.isPresent() && !owner.get().equals(runId)) return -4;
      jdbc.update("INSERT INTO generator.historical_capacity_slot(slot_key,run_id,acquired_at) VALUES (1,?,?) ON CONFLICT (slot_key) DO NOTHING",
          runId, timestamp(now));
    }
    int nextAttempt = row.attemptNo() + 1;
    jdbc.update("UPDATE generator.generation_run SET status='QUEUED',attempt_no=?,finished_at=NULL,stop_requested_at=NULL,"
            + "failure_code=NULL,failure_message=NULL,failure_at=NULL,failure_retryable=NULL,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?",
        nextAttempt, timestamp(now), runId);
    jdbc.update("UPDATE generator.generation_run_reservation SET released_at=NULL,reserved_from=?,reserved_to=? WHERE run_id=?",
        timestamp(reservedFrom), timestamp(reservedTo), runId);
    jdbc.update("INSERT INTO generator.generation_run_attempt(run_id,attempt_no,created_at) VALUES (?,?,?)",
        runId, nextAttempt, timestamp(now));
    return nextAttempt;
  }

  private void releaseReservations(UUID runId, Instant now) {
    jdbc.update("UPDATE generator.generation_run_reservation SET released_at=? WHERE run_id=? AND released_at IS NULL",
        timestamp(now), runId);
  }

  private Optional<RunRow> getForUpdate(UUID id) {
    List<RunRow> rows = jdbc.query("SELECT * FROM generator.generation_run WHERE id=? FOR UPDATE", ROW_MAPPER, id);
    return rows.stream().findFirst();
  }

  private Detail detail(RunRow row) {
    List<UUID> machines = machineIds(row.id());
    Checkpoint checkpoint = checkpoint(row.id()).orElse(null);
    Progress progress = progress(row);
    Failure failure = row.failureCode() == null ? null : new Failure(row.failureCode(), row.failureMessage(),
        Boolean.TRUE.equals(row.failureRetryable()), row.failureAt());
    AnalyticsContext context = new AnalyticsContext(row.enterpriseId(), machines, row.eventFrom(), row.eventTo(), row.generatedThrough());
    return new Detail(row.id(), row.scenarioId(), row.enterpriseId(), row.mode(), row.status(), row.attemptNo(),
        row.randomSeed(), row.requestedAt(), row.scheduledStartAt(), row.startedAt(), row.finishedAt(),
        row.stopRequestedAt(), row.eventFrom(), row.eventTo(), machines, progress, checkpoint, failure, context);
  }

  private Summary summary(RunRow row, int machineCount) {
    return new Summary(row.id(), row.scenarioId(), row.enterpriseId(), row.mode(), row.status(), row.attemptNo(),
        row.requestedAt(), row.scheduledStartAt(), row.startedAt(), row.finishedAt(), machineCount, progress(row));
  }

  private Progress progress(RunRow row) {
    Double percent = null;
    if (!(row.mode() == Mode.SCHEDULED && row.status() == Status.SCHEDULED)) {
      Instant from = row.eventFrom();
      Instant to = row.eventTo();
      if (from != null && to != null && to.isAfter(from)) {
        if (row.status() == Status.COMPLETED) percent = 100d;
        else if (row.generatedThrough() == null) percent = 0d;
        else percent = Math.max(0d, Math.min(100d,
            100d * (row.generatedThrough().toEpochMilli() - from.toEpochMilli()) / (to.toEpochMilli() - from.toEpochMilli())));
      }
    }
    return new Progress(row.currentPhaseCode(), row.cycleNumber(), row.generatedThrough(), percent,
        row.generatedMeasurements(), row.writtenMeasurements(), row.droppedMeasurements(), row.failedBatches(), row.lastSuccessfulWriteAt());
  }

  private List<UUID> machineIds(UUID runId) {
    return jdbc.query("SELECT machine_id FROM generator.generation_run_reservation WHERE run_id=? ORDER BY machine_id",
        (rs, n) -> rs.getObject(1, UUID.class), runId);
  }

  private Optional<Checkpoint> checkpoint(UUID runId) {
    List<Checkpoint> rows = jdbc.query("SELECT completed_through,current_phase_code,cycle_number,producer_sequence "
            + "FROM generator.generation_run_checkpoint WHERE run_id=? AND completed_through IS NOT NULL",
        (rs, n) -> new Checkpoint(instant(rs.getTimestamp("completed_through")), rs.getString("current_phase_code"),
            rs.getLong("cycle_number"), rs.getLong("producer_sequence")), runId);
    return rows.stream().findFirst();
  }

  public static String encodeCursor(Instant requestedAt, UUID id) {
    String value = requestedAt.toEpochMilli() + ":" + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  public static Cursor decodeCursor(String cursor) {
    try {
      String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int colon = value.indexOf(':');
      return new Cursor(Instant.ofEpochMilli(Long.parseLong(value.substring(0, colon))), UUID.fromString(value.substring(colon + 1)));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Invalid cursor");
    }
  }

  private String json(JsonNode value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException("Cannot serialize Run snapshot", exception); }
  }

  private static JsonNode parseSnapshot(String value) {
    try { return new ObjectMapper().readTree(value); }
    catch (Exception exception) { throw new IllegalStateException("Invalid stored Run snapshot", exception); }
  }

  private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
  private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
  private record RunRow(UUID id, UUID scenarioId, UUID enterpriseId, Mode mode, Status status, int attemptNo,
      long randomSeed, Instant requestedAt, Instant scheduledStartAt, Instant startedAt, Instant finishedAt,
      Instant stopRequestedAt, Instant eventFrom, Instant eventTo, String currentPhaseCode, long cycleNumber,
      Instant generatedThrough, long generatedMeasurements, long writtenMeasurements, long droppedMeasurements,
      long failedBatches, Instant lastSuccessfulWriteAt, String failureCode, String failureMessage,
      Instant failureAt, Boolean failureRetryable, JsonNode snapshot) {}
  public record Idempotency(byte[] requestHash, UUID runId) {}
  public record Cursor(Instant requestedAt, UUID id) {}
}
