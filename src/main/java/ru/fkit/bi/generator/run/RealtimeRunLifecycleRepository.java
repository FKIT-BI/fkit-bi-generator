package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.fkit.bi.generator.run.RunModels.Status;

/** Durable lease, status, counters and replay envelope for Realtime attempts. */
@Repository
public class RealtimeRunLifecycleRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;

  public RealtimeRunLifecycleRepository(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(manager);
  }

  public Optional<ClaimedRun> claim(String workerId, Instant now) {
    return claimMode(workerId, now, "REALTIME", "SCHEDULED");
  }

  public Optional<ClaimedRun> claimHistorical(String workerId, Instant now) {
    return claimMode(workerId, now, "HISTORICAL");
  }

  private Optional<ClaimedRun> claimMode(String workerId, Instant now, String... modes) {
    return tx.execute(status -> {
      String modePlaceholders=String.join(",",java.util.Collections.nCopies(modes.length,"?"));
      List<ClaimedRun> rows = jdbc.query("SELECT id,snapshot,random_seed,attempt_no,status,started_at,event_from,event_to,"
              + "generated_through,generated_measurements,written_measurements,dropped_measurements,failed_batches "
              + "FROM generator.generation_run WHERE mode IN ("+modePlaceholders+") AND "
              + "(status='QUEUED' OR (status IN ('RUNNING','STOP_REQUESTED') AND lease_until < ?)) "
              + "ORDER BY requested_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
          (rs, n) -> new ClaimedRun(rs.getObject("id", UUID.class), read(rs.getString("snapshot")),
              rs.getLong("random_seed"), rs.getInt("attempt_no"), Status.valueOf(rs.getString("status")), null, instant(rs.getTimestamp("started_at")),
              null,
              instant(rs.getTimestamp("event_from")), instant(rs.getTimestamp("event_to")),
              instant(rs.getTimestamp("generated_through")), rs.getLong("generated_measurements"),
              rs.getLong("written_measurements"), rs.getLong("dropped_measurements"), rs.getLong("failed_batches")),
          append(modes,Timestamp.from(now)));
      if (rows.isEmpty()) return Optional.empty();
      ClaimedRun run = rows.getFirst();
      Instant started = run.startedAt() == null ? now : run.startedAt();
      Instant eventFrom = run.eventFrom() == null ? started : run.eventFrom();
      Instant to = run.eventTo();
      if (to == null) {
        JsonNode execution = run.snapshot().path("execution");
        long durationMs = execution.path("durationMs").asLong(0);
        // A LOOP without an explicit duration is intentionally open-ended. Only
        // ONCE has a finite implicit end (the sum of its phase durations).
        if (durationMs <= 0 && "ONCE".equals(run.snapshot().path("cycleMode").asText())) {
          durationMs = sequenceDuration(run.snapshot());
        }
        if (durationMs > 0) to = started.plusMillis(durationMs);
      }
      jdbc.update("UPDATE generator.generation_run SET status=CASE WHEN status='QUEUED' THEN 'RUNNING' ELSE status END,started_at=COALESCE(started_at,?),"
              + "event_from=COALESCE(event_from,?),event_to=COALESCE(event_to,?),lease_owner=?,lease_until=?,updated_at=? WHERE id=?",
          Timestamp.from(started), Timestamp.from(started), timestamp(to), workerId,
          Timestamp.from(now.plusSeconds(30)), Timestamp.from(now), run.id());
      jdbc.update("UPDATE generator.generation_run_attempt SET started_at=COALESCE(started_at,?) WHERE run_id=? AND attempt_no=?",
          Timestamp.from(started), run.id(), run.attemptNo());
      Instant attemptStarted = jdbc.queryForObject("SELECT started_at FROM generator.generation_run_attempt WHERE run_id=? AND attempt_no=?",
          (rs, n) -> instant(rs.getTimestamp(1)), run.id(), run.attemptNo());
      return Optional.of(new ClaimedRun(run.id(), run.snapshot(), run.randomSeed(), run.attemptNo(), run.status(), workerId, started,
          attemptStarted, eventFrom, to, run.generatedThrough(), run.generatedMeasurements(), run.writtenMeasurements(),
          run.droppedMeasurements(), run.failedBatches()));
    });
  }

  private Object[] append(String[] modes,Object last) { Object[] values=java.util.Arrays.copyOf(modes,modes.length+1,Object[].class);values[modes.length]=last;return values; }

  public Instant databaseNow() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  public State state(UUID id) {
    return jdbc.queryForObject("SELECT status,lease_owner,started_at,event_from,event_to,generated_through,generated_measurements,"
            + "written_measurements,dropped_measurements,failed_batches FROM generator.generation_run WHERE id=?",
        (rs, n) -> new State(Status.valueOf(rs.getString("status")), rs.getString("lease_owner"), instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("event_from")), instant(rs.getTimestamp("event_to")),
            instant(rs.getTimestamp("generated_through")), rs.getLong("generated_measurements"),
            rs.getLong("written_measurements"), rs.getLong("dropped_measurements"), rs.getLong("failed_batches")), id);
  }

  public JsonNode checkpoint(UUID id) {
    List<String> rows = jdbc.query("SELECT checkpoint::text FROM generator.generation_run_checkpoint WHERE run_id=?",
        (rs, n) -> rs.getString(1), id);
    return rows.isEmpty() ? mapper.createObjectNode() : read(rows.getFirst());
  }

  public long producerSequence(UUID id) {
    List<Long> rows = jdbc.query("SELECT producer_sequence FROM generator.generation_run_checkpoint WHERE run_id=?",
        (rs, n) -> rs.getLong(1), id);
    return rows.isEmpty() ? 0L : rows.getFirst();
  }

  public void recordSkipped(UUID id, String workerId, JsonNode checkpoint, Instant through, String phase, long cycle,
      long generatedDelta, long droppedDelta) {
    tx.executeWithoutResult(status -> {
      jdbc.update("UPDATE generator.generation_run SET generated_through=?,current_phase_code=?,cycle_number=?,"
              + "generated_measurements=generated_measurements+?,dropped_measurements=dropped_measurements+?,"
              + "lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() WHERE id=? AND status='RUNNING' AND lease_owner=?",
          timestamp(through), phase, cycle, generatedDelta, droppedDelta, id, workerId);
      requireUpdated(id, workerId);
      jdbc.update("INSERT INTO generator.generation_run_checkpoint(run_id,checkpoint,generated_measurements,dropped_measurements,updated_at) "
              + "VALUES (?,?::jsonb,?,?,clock_timestamp()) ON CONFLICT (run_id) DO UPDATE SET checkpoint=EXCLUDED.checkpoint,"
              + "generated_measurements=EXCLUDED.generated_measurements,dropped_measurements=EXCLUDED.dropped_measurements,updated_at=clock_timestamp()",
          id, checkpoint.toString(), state(id).generatedMeasurements(), state(id).droppedMeasurements());
    });
  }

  /** Persist pending batch before I/O: a restart replays the identical body under the identical id. */
  public void pending(UUID id, String workerId, long sequence, JsonNode checkpoint, long generated, long dropped) {
    int renewed = jdbc.update("UPDATE generator.generation_run SET lease_until=clock_timestamp()+interval '30 seconds' "
        + "WHERE id=? AND lease_owner=? AND status IN ('RUNNING','STOP_REQUESTED')", id, workerId);
    if (renewed == 0) throw new LeaseLostException();
    jdbc.update("INSERT INTO generator.generation_run_checkpoint(run_id,producer_sequence,checkpoint,generated_measurements,"
            + "dropped_measurements,updated_at) VALUES (?,?,?::jsonb,?,?,clock_timestamp()) "
            + "ON CONFLICT (run_id) DO UPDATE SET producer_sequence=EXCLUDED.producer_sequence,checkpoint=EXCLUDED.checkpoint,"
            + "generated_measurements=EXCLUDED.generated_measurements,dropped_measurements=EXCLUDED.dropped_measurements,updated_at=clock_timestamp()",
        id, sequence, checkpoint.toString(), generated, dropped);
  }

  public void acknowledged(UUID id, String workerId, long sequence, JsonNode checkpoint, Instant through, String phase, long cycle,
      long generatedDelta, long writtenDelta, long droppedDelta) {
    tx.executeWithoutResult(status -> {
      jdbc.update("UPDATE generator.generation_run SET generated_through=?,current_phase_code=?,cycle_number=?,"
              + "generated_measurements=generated_measurements+?,written_measurements=written_measurements+?,"
              + "dropped_measurements=dropped_measurements+?,last_successful_write_at=clock_timestamp(),"
              + "lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() WHERE id=? AND status IN ('RUNNING','STOP_REQUESTED') AND lease_owner=?",
          timestamp(through), phase, cycle, generatedDelta, writtenDelta, droppedDelta, id, workerId);
      requireUpdated(id, workerId);
      pending(id, workerId, sequence, checkpoint, state(id).generatedMeasurements(), state(id).droppedMeasurements());
      jdbc.update("UPDATE generator.generation_run_checkpoint SET written_measurements=(SELECT written_measurements FROM generator.generation_run WHERE id=?),updated_at=clock_timestamp() WHERE run_id=?",
          id, id);
    });
  }

  public void droppedBatch(UUID id, String workerId, long sequence, JsonNode checkpoint, Instant through, String phase, long cycle,
      long generatedDelta, long droppedDelta) {
    tx.executeWithoutResult(status -> {
      jdbc.update("UPDATE generator.generation_run SET generated_through=?,current_phase_code=?,cycle_number=?,"
              + "generated_measurements=generated_measurements+?,dropped_measurements=dropped_measurements+?,"
              + "failed_batches=failed_batches+1,lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() WHERE id=? AND lease_owner=?",
          timestamp(through), phase, cycle, generatedDelta, droppedDelta, id, workerId);
      requireUpdated(id, workerId);
      State current = state(id);
      pending(id, workerId, sequence, checkpoint, current.generatedMeasurements(), current.droppedMeasurements());
    });
  }

  public void heartbeat(UUID id, String workerId) {
    jdbc.update("UPDATE generator.generation_run SET lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() "
        + "WHERE id=? AND lease_owner=? AND status IN ('RUNNING','STOP_REQUESTED')", id, workerId);
    requireUpdated(id, workerId);
  }

  public void terminal(UUID id, String workerId, Status finalStatus) {
    tx.executeWithoutResult(status -> {
      int changed = jdbc.update("UPDATE generator.generation_run SET status=?,finished_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=? AND lease_owner=?",
          finalStatus.name(), id, workerId);
      if (changed == 0) throw new LeaseLostException();
      jdbc.update("UPDATE generator.generation_run_attempt SET finished_at=clock_timestamp() WHERE run_id=? AND attempt_no=(SELECT attempt_no FROM generator.generation_run WHERE id=?)",
          id, id);
      jdbc.update("UPDATE generator.generation_run_reservation SET released_at=clock_timestamp() WHERE run_id=? AND released_at IS NULL", id);
      jdbc.update("DELETE FROM generator.historical_capacity_slot WHERE run_id=?",id);
    });
  }

  public void failed(UUID id, String workerId, String code, String message, boolean retryable, long failedBatches) {
    tx.executeWithoutResult(status -> {
      String safe = message == null ? "Run execution failed" : message.substring(0, Math.min(500, message.length()));
      int changed = jdbc.update("UPDATE generator.generation_run SET status='FAILED',finished_at=clock_timestamp(),failure_code=?,failure_message=?,"
              + "failure_at=clock_timestamp(),failure_retryable=?,failed_batches=failed_batches+?,lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=? AND lease_owner=?",
          code, safe, retryable, failedBatches, id, workerId);
      if (changed == 0) throw new LeaseLostException();
      jdbc.update("UPDATE generator.generation_run_attempt SET finished_at=clock_timestamp(),failure_code=?,failure_message=?,"
              + "failure_at=clock_timestamp(),failure_retryable=? WHERE run_id=? AND attempt_no=(SELECT attempt_no FROM generator.generation_run WHERE id=?)",
          code, safe, retryable, id, id);
      jdbc.update("UPDATE generator.generation_run_reservation SET released_at=clock_timestamp() WHERE run_id=? AND released_at IS NULL", id);
      jdbc.update("DELETE FROM generator.historical_capacity_slot WHERE run_id=?", id);
    });
  }

  public void historicalPending(UUID id,String workerId,long sequence,JsonNode checkpoint) {
    int renewed=jdbc.update("UPDATE generator.generation_run SET lease_until=clock_timestamp()+interval '30 seconds' WHERE id=? AND lease_owner=? AND status IN ('RUNNING','STOP_REQUESTED')",id,workerId);
    if(renewed==0)throw new LeaseLostException();
    jdbc.update("INSERT INTO generator.generation_run_checkpoint(run_id,producer_sequence,checkpoint,updated_at) VALUES (?,?,?::jsonb,clock_timestamp()) "
        + "ON CONFLICT (run_id) DO UPDATE SET producer_sequence=GREATEST(generator.generation_run_checkpoint.producer_sequence,EXCLUDED.producer_sequence),checkpoint=EXCLUDED.checkpoint,updated_at=clock_timestamp()",
        id,sequence,checkpoint.toString());
  }

  public void historicalAcknowledge(UUID id,String workerId,long sequence,JsonNode checkpoint,long count,Instant through) {
    tx.executeWithoutResult(status -> {
      String stored=jdbc.queryForObject("SELECT checkpoint::text FROM generator.generation_run_checkpoint WHERE run_id=?",String.class,id);
      JsonNode current=read(stored);
      long acknowledged=current.path("lastAcknowledgedSequence").asLong(0);
      if(sequence>acknowledged){
        jdbc.update("UPDATE generator.generation_run SET generated_measurements=generated_measurements+?,written_measurements=written_measurements+?,"
                + "generated_through=GREATEST(COALESCE(generated_through,?),?),last_successful_write_at=clock_timestamp(),"
                + "lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() "
                + "WHERE id=? AND lease_owner=? AND status IN ('RUNNING','STOP_REQUESTED')",
            count,count,timestamp(through),timestamp(through),id,workerId);
        requireUpdated(id,workerId);
      } else {
        int renewed=jdbc.update("UPDATE generator.generation_run SET lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() "
            + "WHERE id=? AND lease_owner=? AND status IN ('RUNNING','STOP_REQUESTED')",id,workerId);
        if(renewed==0)throw new LeaseLostException();
      }
      jdbc.update("UPDATE generator.generation_run_checkpoint SET checkpoint=?::jsonb,"
              + "generated_measurements=(SELECT generated_measurements FROM generator.generation_run WHERE id=?),"
              + "written_measurements=(SELECT written_measurements FROM generator.generation_run WHERE id=?),updated_at=clock_timestamp() WHERE run_id=?",
          checkpoint.toString(),id,id,id);
    });
  }

  @org.springframework.transaction.annotation.Transactional
  public void completeHistoricalChunk(UUID id,String workerId,JsonNode checkpoint,Instant through,String phase,long cycle,
      long dropped,long failedBatches,List<UUID> machineIds) {
    jdbc.update("UPDATE generator.generation_run SET generated_through=?,current_phase_code=?,cycle_number=?,"
        + "dropped_measurements=dropped_measurements+?,failed_batches=failed_batches+?,last_successful_write_at=clock_timestamp(),"
        + "lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() WHERE id=? AND lease_owner=? AND status IN ('RUNNING','STOP_REQUESTED')",
        timestamp(through),phase,cycle,dropped,failedBatches,id,workerId);
    requireUpdated(id,workerId);
    jdbc.update("INSERT INTO generator.generation_run_checkpoint(run_id,completed_through,current_phase_code,cycle_number,producer_sequence,checkpoint,generated_measurements,written_measurements,dropped_measurements,failed_batches,updated_at) "
        + "VALUES (?,?,?,?,?,?::jsonb,(SELECT generated_measurements FROM generator.generation_run WHERE id=?),(SELECT written_measurements FROM generator.generation_run WHERE id=?),(SELECT dropped_measurements FROM generator.generation_run WHERE id=?),(SELECT failed_batches FROM generator.generation_run WHERE id=?),clock_timestamp()) "
        + "ON CONFLICT (run_id) DO UPDATE SET completed_through=EXCLUDED.completed_through,current_phase_code=EXCLUDED.current_phase_code,cycle_number=EXCLUDED.cycle_number,producer_sequence=GREATEST(generator.generation_run_checkpoint.producer_sequence,EXCLUDED.producer_sequence),checkpoint=EXCLUDED.checkpoint,generated_measurements=EXCLUDED.generated_measurements,written_measurements=EXCLUDED.written_measurements,dropped_measurements=EXCLUDED.dropped_measurements,failed_batches=EXCLUDED.failed_batches,updated_at=clock_timestamp()",
        id,timestamp(through),phase,cycle,producerSequence(id),checkpoint.toString(),id,id,id,id);
    Instant chunkFrom=Instant.ofEpochMilli(checkpoint.path("chunkFrom").asLong());
    for(UUID machineId:machineIds)jdbc.update("INSERT INTO generator.historical_coverage(id,enterprise_id,run_id,machine_id,event_from,event_to) "
        + "SELECT gen_random_uuid(),enterprise_id,id,?,?,? FROM generator.generation_run WHERE id=? "
        + "AND NOT EXISTS (SELECT 1 FROM generator.historical_coverage c WHERE c.run_id=? AND c.machine_id=? AND c.event_from=? AND c.event_to=?)",
        machineId,timestamp(chunkFrom),timestamp(through),id,id,machineId,timestamp(chunkFrom),timestamp(through));
  }

  private long sequenceDuration(JsonNode snapshot) {
    long total = 0;
    for (JsonNode phase : snapshot.path("scenario").path("phases")) {
      long duration = phase.path("durationMs").asLong(0);
      if (duration <= 0) return 0;
      total = Math.addExact(total, duration);
    }
    return total;
  }

  private JsonNode read(String json) {
    try { return mapper.readTree(json); }
    catch (Exception e) { throw new IllegalStateException("Stored realtime execution data is invalid", e); }
  }
  private void requireUpdated(UUID id, String workerId) {
    List<Integer> rows = jdbc.query("SELECT 1 FROM generator.generation_run WHERE id=? AND lease_owner=?",
        (rs, n) -> rs.getInt(1), id, workerId);
    if (rows.isEmpty()) throw new LeaseLostException();
  }
  private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
  private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

  public record ClaimedRun(UUID id, JsonNode snapshot, long randomSeed, int attemptNo, Status status, String workerId, Instant startedAt,
      Instant attemptStartedAt,
      Instant eventFrom, Instant eventTo, Instant generatedThrough, long generatedMeasurements,
      long writtenMeasurements, long droppedMeasurements, long failedBatches) {}
  public record State(Status status, String leaseOwner, Instant startedAt, Instant eventFrom, Instant eventTo, Instant generatedThrough,
      long generatedMeasurements, long writtenMeasurements, long droppedMeasurements, long failedBatches) {}
}
