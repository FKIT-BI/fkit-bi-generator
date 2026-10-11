package ru.fkit.bi.generator.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class JdbcTelemetrySink implements TelemetrySink {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final TelemetryBatchHasher hasher = new TelemetryBatchHasher();
  private final MeterRegistry meters;
  private final long futureSkewMs;
  private final AtomicLong lastSuccessfulWriteMillis = new AtomicLong();

  public JdbcTelemetrySink(JdbcTemplate jdbc, PlatformTransactionManager txManager,
      MeterRegistry meters, @Value("${telemetry.future-skew-ms:5000}") long futureSkewMs) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txManager);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.tx.setTimeout(3);
    this.meters = meters;
    this.futureSkewMs = futureSkewMs;
    meters.counter("telemetry.generated.measurements");
    meters.counter("telemetry.accepted.measurements");
    meters.counter("telemetry.rejected.measurements");
    meters.counter("telemetry.failed.batches");
    meters.counter("telemetry.dropped.measurements");
    meters.gauge("telemetry.last.successful.write.epoch.millis", lastSuccessfulWriteMillis);
    meters.gauge("telemetry.buffer.depth", new AtomicLong(0));
  }

  @Override
  public TelemetryWriteResult accept(TelemetryBatch batch) {
    meters.counter("telemetry.generated.measurements").increment(batch.measurements().size());
    try {
      validateEnvelope(batch);
      byte[] payloadHash = hasher.hash(batch);
      int retry = 0;
      while (true) {
        try {
          Integer accepted = tx.execute(status -> persist(batch, payloadHash));
          if (accepted != null && accepted > 0) meters.counter("telemetry.accepted.measurements").increment(accepted);
          lastSuccessfulWriteMillis.set(System.currentTimeMillis());
          return TelemetryWriteResult.committed(batch.measurements().size());
        } catch (TelemetryRetryableException | TransientDataAccessException retryable) {
          if (retry++ < 2) {
            try {
              Thread.sleep(25L << retry);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new TelemetryRetryableException("WRITE_INTERRUPTED", interrupted);
            }
            continue;
          }
          if (batch.mode() == TelemetryBatch.Mode.REALTIME) {
            meters.counter("telemetry.dropped.measurements").increment(batch.measurements().size());
            meters.counter("telemetry.failed.batches").increment();
            return TelemetryWriteResult.dropped(batch.measurements().size());
          }
          throw retryable;
        }
      }
    } catch (RuntimeException e) {
      if (e instanceof TelemetryValidationException) {
        meters.counter("telemetry.rejected.measurements").increment(batch.measurements().size());
      }
      if (!(e instanceof TelemetryRetryableException)) meters.counter("telemetry.failed.batches").increment();
      throw e;
    }
  }

  private int persist(TelemetryBatch batch, byte[] payloadHash) {
    jdbc.execute("SET LOCAL lock_timeout = '250ms'");
    jdbc.execute("SET LOCAL statement_timeout = '2500ms'");
    Long existingId = insertHeader(batch, payloadHash);
    if (existingId == null) {
      ExistingBatch existing = jdbc.queryForObject(
          "SELECT id, payload_hash FROM telemetry.ingest_batch WHERE producer_id=? AND producer_batch_id=?",
          (rs, row) -> new ExistingBatch(rs.getLong(1), rs.getBytes(2)),
          batch.producerId(), batch.producerBatchId());
      if (!java.security.MessageDigest.isEqual(payloadHash, existing.payloadHash())) {
        throw new TelemetryConflictException("BATCH_ID_REUSED_WITH_DIFFERENT_PAYLOAD");
      }
      return 0;
    }

    Map<UUID, SignalSnapshot> signals = loadAndLockSignals(batch);
    Map<LocalDate, Boolean> days = new HashMap<>();
    for (var measurement : batch.measurements()) {
      SignalSnapshot signal = signals.get(measurement.signalId());
      validateMeasurement(batch, measurement, signal);
      days.put(measurement.observedAt().atZone(ZoneOffset.UTC).toLocalDate(), Boolean.TRUE);
    }
    for (LocalDate day : days.keySet().stream().sorted().toList()) {
      Boolean locked = jdbc.queryForObject(
          "SELECT pg_try_advisory_xact_lock_shared(telemetry.raw_partition_day_lock_key(?))",
          Boolean.class, java.sql.Date.valueOf(day));
      if (!Boolean.TRUE.equals(locked)) throw new TelemetryRetryableException("RAW_PARTITION_LOCK_BUSY");
      Integer active = jdbc.queryForObject(
          "SELECT count(*) FROM telemetry.raw_partition_registry WHERE partition_day=? AND status='ACTIVE'",
          Integer.class, java.sql.Date.valueOf(day));
      if (active == null || active != 1) throw new TelemetryRetryableException("RAW_PARTITION_NOT_ACTIVE");
    }
    insertSignalUsage(batch, signals);
    insertRaw(batch, signals);
    insertHints(existingId, batch, signals);
    return batch.measurements().size();
  }

  private Long insertHeader(TelemetryBatch batch, byte[] hash) {
    Instant min = batch.measurements().stream().map(TelemetryMeasurement::observedAt).min(Comparator.naturalOrder()).orElseThrow();
    Instant max = batch.measurements().stream().map(TelemetryMeasurement::observedAt).max(Comparator.naturalOrder()).orElseThrow();
    List<Long> ids = jdbc.query(
        "INSERT INTO telemetry.ingest_batch(producer_id,producer_batch_id,producer_sequence,payload_hash,"
            + "generator_run_id,mode,measurement_count,min_observed_at,max_observed_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (producer_id,producer_batch_id) DO NOTHING RETURNING id",
        (rs, row) -> rs.getLong(1), batch.producerId(), batch.producerBatchId(), batch.producerSequence(),
        hash, batch.generatorRunId(), batch.mode().code(), batch.measurements().size(),
        Timestamp.from(min), Timestamp.from(max));
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private Map<UUID, SignalSnapshot> loadAndLockSignals(TelemetryBatch batch) {
    var signalIds = batch.measurements().stream().map(TelemetryMeasurement::signalId).distinct().sorted().toList();
    Map<UUID, SignalSnapshot> result = new HashMap<>();
    for (UUID id : signalIds) {
      Boolean locked = jdbc.queryForObject(
          "SELECT pg_try_advisory_xact_lock_shared(hashtextextended(?::text, 0))", Boolean.class, id);
      if (!Boolean.TRUE.equals(locked)) throw new TelemetryRetryableException("SIGNAL_CONFIGURATION_LOCK_BUSY");
      List<SignalSnapshot> rows = jdbc.query(
          "SELECT s.machine_id, s.value_type, s.valid_min, s.valid_max, s.status "
              + "FROM equipment.telemetry_signal s WHERE s.id=?",
          (rs, row) -> new SignalSnapshot(id, rs.getObject("machine_id", UUID.class),
              rs.getString("value_type"), (Double) rs.getObject("valid_min"),
              (Double) rs.getObject("valid_max"), rs.getString("status")), id);
      if (rows.size() != 1) throw new TelemetryValidationException("UNKNOWN_SIGNAL");
      result.put(id, rows.getFirst());
    }
    return result;
  }

  private void validateMeasurement(TelemetryBatch batch, TelemetryMeasurement measurement, SignalSnapshot signal) {
    if (!"ACTIVE".equals(signal.status())) throw new TelemetryValidationException("SIGNAL_NOT_ACTIVE");
    String type = switch (measurement.valueTypeCode()) {
      case 1 -> "DOUBLE";
      case 2 -> "LONG";
      case 3 -> "BOOLEAN";
      case 4 -> "STRING";
      default -> throw new TelemetryValidationException("UNSUPPORTED_VALUE_TYPE");
    };
    if (!type.equals(signal.valueType())) throw new TelemetryValidationException("SIGNAL_VALUE_TYPE_MISMATCH");
    Instant timestamp = measurement.observedAt();
    Instant now = Instant.now();
    if (batch.mode() == TelemetryBatch.Mode.REALTIME && timestamp.isAfter(now.plusMillis(futureSkewMs))) {
      throw new TelemetryValidationException("REALTIME_EVENT_TOO_FAR_IN_FUTURE");
    }
    Instant historicalStart = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(89)
        .atStartOfDay(ZoneOffset.UTC).toInstant();
    if (batch.mode() == TelemetryBatch.Mode.HISTORICAL
        && (timestamp.isAfter(now) || timestamp.isBefore(historicalStart))) {
      throw new TelemetryValidationException("HISTORICAL_EVENT_OUTSIDE_RETENTION_WINDOW");
    }
    if (measurement.value() instanceof String text && text.length() > 500) {
      throw new TelemetryValidationException("STRING_VALUE_TOO_LONG");
    }
    if (measurement.value() instanceof Double value && !Double.isFinite(value)) {
      throw new TelemetryValidationException("NON_FINITE_VALUE");
    }
  }

  private void insertSignalUsage(TelemetryBatch batch, Map<UUID, SignalSnapshot> signals) {
    Map<UUID, Instant> firstObserved = new HashMap<>();
    batch.measurements().forEach(m -> firstObserved.merge(m.signalId(), m.observedAt(),
        (left, right) -> left.isBefore(right) ? left : right));
    Instant receivedAt = jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    for (UUID signalId : signals.keySet()) {
      jdbc.update("INSERT INTO telemetry.signal_usage(signal_id,first_observed_at,first_received_at) "
              + "VALUES (?,?,?) ON CONFLICT (signal_id) DO NOTHING",
          signalId, Timestamp.from(firstObserved.get(signalId)), Timestamp.from(receivedAt));
    }
  }

  private void insertRaw(TelemetryBatch batch, Map<UUID, SignalSnapshot> signals) {
    String sql = "INSERT INTO telemetry.raw_measurement(ingest_batch_id,ordinal,signal_id,observed_at,"
        + "value_double,value_long,value_boolean,value_text,source_quality,validation_flags) VALUES (?,?,?,?,?,?,?,?,?,?)";
    long batchId = headerId(batch);
    jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
      @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
        TelemetryMeasurement m = batch.measurements().get(i);
        SignalSnapshot signal = signals.get(m.signalId());
        ps.setLong(1, batchId);
        ps.setInt(2, i);
        ps.setObject(3, m.signalId());
        ps.setTimestamp(4, Timestamp.from(m.observedAt()));
        Object value = m.value();
        if (value instanceof Double d) ps.setDouble(5, d); else ps.setNull(5, Types.DOUBLE);
        if (value instanceof Long l) ps.setLong(6, l); else ps.setNull(6, Types.BIGINT);
        if (value instanceof Boolean b) ps.setBoolean(7, b); else ps.setNull(7, Types.BOOLEAN);
        if (value instanceof String s) ps.setString(8, s); else ps.setNull(8, Types.VARCHAR);
        ps.setShort(9, m.sourceQuality());
        int flags = value instanceof Number number && outOfRange(number.doubleValue(), signal) ? 1 : 0;
        if (m.observedAt().isAfter(Instant.now())) flags |= 2;
        ps.setInt(10, flags);
      }
      @Override public int getBatchSize() { return batch.measurements().size(); }
    });
  }

  private long headerId(TelemetryBatch batch) {
    Long id = jdbc.queryForObject("SELECT id FROM telemetry.ingest_batch WHERE producer_id=? AND producer_batch_id=?",
        Long.class, batch.producerId(), batch.producerBatchId());
    return id;
  }

  private void insertHints(long batchId, TelemetryBatch batch, Map<UUID, SignalSnapshot> signals) {
    record Range(Instant from, Instant to) { }
    Map<UUID, Range> ranges = new HashMap<>();
    for (TelemetryMeasurement m : batch.measurements()) {
      UUID machineId = signals.get(m.signalId()).machineId();
      Instant exclusive = m.observedAt().plusMillis(1);
      ranges.merge(machineId, new Range(m.observedAt(), exclusive), (a, b) -> new Range(
          a.from().isBefore(b.from()) ? a.from() : b.from(), a.to().isAfter(b.to()) ? a.to() : b.to()));
    }
    ranges.forEach((machineId, range) -> jdbc.update(
        "INSERT INTO telemetry.recalculation_hint(ingest_batch_id,machine_id,from_observed_at,to_observed_at) "
            + "VALUES (?,?,?,?)", batchId, machineId, Timestamp.from(range.from()), Timestamp.from(range.to())));
  }

  private boolean outOfRange(double value, SignalSnapshot signal) {
    return (signal.validMin() != null && value < signal.validMin())
        || (signal.validMax() != null && value > signal.validMax());
  }

  private void validateEnvelope(TelemetryBatch batch) {
    if (batch.producerId().isBlank() || batch.producerId().length() > 128
        || batch.producerBatchId().isBlank() || batch.producerBatchId().length() > 128
        || batch.producerSequence() < 0) throw new TelemetryValidationException("INVALID_BATCH_ENVELOPE");
    if (batch.measurements().isEmpty() || batch.measurements().size() > 1000) {
      throw new TelemetryValidationException("BATCH_SIZE_OUT_OF_RANGE");
    }
  }

  private record ExistingBatch(long id, byte[] payloadHash) { }
  private record SignalSnapshot(UUID id, UUID machineId, String valueType, Double validMin, Double validMax, String status) { }
}
