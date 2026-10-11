package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ru.fkit.bi.generator.run.RunModels.Status;
import ru.fkit.bi.generator.telemetry.*;

/** Bounded one-batch-at-a-time realtime executor. */
@Component
public class RealtimeRunExecutor {
  private static final int MAX_BATCH = 1000;
  private static final long LOOP_DELAY_MS = 100;
  private final RealtimeRunLifecycleRepository repository;
  private final RealtimeTelemetryExecutionService telemetry;
  private final ObjectMapper mapper;
  private final RealtimeBehaviorEvaluator evaluator = new RealtimeBehaviorEvaluator();

  public RealtimeRunExecutor(RealtimeRunLifecycleRepository repository,
      RealtimeTelemetryExecutionService telemetry, ObjectMapper mapper) {
    this.repository = repository;
    this.telemetry = telemetry;
    this.mapper = mapper;
  }

  public void execute(RealtimeRunLifecycleRepository.ClaimedRun claim) {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        RealtimeRunLifecycleRepository.State state = repository.state(claim.id());
        if (!claim.workerId().equals(state.leaseOwner())) return;
        JsonNode checkpoint = repository.checkpoint(claim.id());
        if (state.status() == Status.STOP_REQUESTED) {
          if (checkpoint.has("pendingBatch")) publishPending(claim, checkpoint);
          repository.terminal(claim.id(), claim.workerId(), Status.STOPPED);
          return;
        }
        if (state.status() != Status.RUNNING) return;
        if (checkpoint.has("pendingBatch")) {
          publishPending(claim, checkpoint);
          continue;
        }
        Instant now = repository.databaseNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (state.eventTo() != null && !now.isBefore(state.eventTo())) {
          repository.terminal(claim.id(), claim.workerId(), Status.COMPLETED);
          return;
        }
        Draft draft = nextDraft(claim, checkpoint, now);
        if (draft == null) {
          repository.heartbeat(claim.id(), claim.workerId());
          Thread.sleep(LOOP_DELAY_MS);
          continue;
        }
        if (draft.measurements().isEmpty()) {
          repository.recordSkipped(claim.id(), claim.workerId(), draft.checkpoint(), draft.through(), draft.phase(), draft.cycle(),
              draft.generated(), draft.dropped());
          Thread.sleep(LOOP_DELAY_MS);
          continue;
        }
        long sequence = repository.producerSequence(claim.id()) + 1;
        String batchId = claim.id() + ":rt:" + sequence;
        ObjectNode pending = draft.checkpoint().deepCopy();
        ObjectNode envelope = pending.putObject("pendingBatch");
        envelope.put("sequence", sequence);
        envelope.put("batchId", batchId);
        envelope.put("phase", draft.phase());
        envelope.put("cycle", draft.cycle());
        envelope.put("through", draft.through().toEpochMilli());
        envelope.put("generated", draft.generated());
        envelope.put("dropped", draft.dropped());
        ArrayNode measurements = envelope.putArray("measurements");
        for (TelemetryMeasurement measurement : draft.measurements()) measurements.add(encode(measurement));
        repository.pending(claim.id(), claim.workerId(), sequence, pending, state.generatedMeasurements(), state.droppedMeasurements());
        publishPending(claim, pending);
      }
    } catch (LeaseLostException lost) {
      return;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); // keep RUNNING lease; another process resumes after expiry
    } catch (TelemetryConflictException exception) {
      repository.failed(claim.id(), claim.workerId(), "TELEMETRY_CONFLICT", exception.getMessage(), false, 1);
    } catch (TelemetryValidationException | IllegalArgumentException exception) {
      repository.failed(claim.id(), claim.workerId(), "GENERATION_FAILED", safeMessage(exception), false, 1);
    } catch (DataAccessException | TelemetryRetryableException exception) {
      repository.failed(claim.id(), claim.workerId(), "TELEMETRY_UNAVAILABLE", safeMessage(exception), true, 1);
    } catch (RuntimeException exception) {
      repository.failed(claim.id(), claim.workerId(), "GENERATION_FAILED", safeMessage(exception), false, 1);
    }
  }

  private void publishPending(RealtimeRunLifecycleRepository.ClaimedRun claim, JsonNode checkpoint) {
    JsonNode pending = checkpoint.path("pendingBatch");
    long sequence = pending.path("sequence").asLong();
    List<TelemetryMeasurement> measurements = new ArrayList<>();
    for (JsonNode row : pending.path("measurements")) measurements.add(decode(row));
    TelemetryWriteResult result = telemetry.publish("fkit-generator", pending.path("batchId").asText(), sequence,
        claim.id(), measurements);
    ObjectNode clean = checkpoint.deepCopy();
    clean.remove("pendingBatch");
    Instant through = Instant.ofEpochMilli(pending.path("through").asLong());
    if (result.droppedMeasurements() > 0) {
      repository.droppedBatch(claim.id(), claim.workerId(), sequence, clean, through, pending.path("phase").asText(),
          pending.path("cycle").asLong(), pending.path("generated").asLong(),
          pending.path("dropped").asLong() + result.droppedMeasurements());
    } else {
      repository.acknowledged(claim.id(), claim.workerId(), sequence, clean, through, pending.path("phase").asText(),
          pending.path("cycle").asLong(), pending.path("generated").asLong(), result.writtenMeasurements(),
          pending.path("dropped").asLong());
    }
  }

  private Draft nextDraft(RealtimeRunLifecycleRepository.ClaimedRun claim, JsonNode checkpoint, Instant now) {
    JsonNode snapshot = claim.snapshot();
    List<JsonNode> phases = nodes(snapshot.path("scenario").path("phases"));
    if (phases.isEmpty()) throw new IllegalArgumentException("Run snapshot has no phases");
    long sequenceDuration = phases.stream().mapToLong(p -> p.path("durationMs").asLong()).sum();
    if (sequenceDuration <= 0) throw new IllegalArgumentException("Run snapshot phase duration is invalid");
    long elapsed = Math.max(0, now.toEpochMilli() - claim.attemptStartedAt().toEpochMilli());
    boolean loop = "LOOP".equals(snapshot.path("cycleMode").asText());
    if (!loop && elapsed >= sequenceDuration) return null;
    long cycle = loop ? elapsed / sequenceDuration : 0;
    long withinCycle = loop ? elapsed % sequenceDuration : elapsed;
    long phaseOffset = 0;
    JsonNode currentPhase = null;
    long phaseElapsed = 0;
    for (JsonNode phase : phases) {
      long duration = phase.path("durationMs").asLong();
      if (withinCycle < phaseOffset + duration) {
        currentPhase = phase;
        phaseElapsed = withinCycle - phaseOffset;
        break;
      }
      phaseOffset += duration;
    }
    if (currentPhase == null) return null;
    String phaseCode = currentPhase.path("code").asText();
    Instant phaseStart = claim.attemptStartedAt().plusMillis(cycle * sequenceDuration + phaseOffset);
    ObjectNode resultCheckpoint = checkpoint.deepCopy();
    ObjectNode slots = resultCheckpoint.has("lastTickSlots")
        ? (ObjectNode) resultCheckpoint.get("lastTickSlots") : resultCheckpoint.putObject("lastTickSlots");
    List<JsonNode> machines = nodes(snapshot.path("machines"));
    machines.sort(java.util.Comparator.comparing(machine -> machine.path("machineId").asText()));
    List<TelemetryMeasurement> measurements = new ArrayList<>(MAX_BATCH);
    long dropped = 0, generatedCount = 0;
    Instant through = phaseStart.plusMillis(phaseElapsed);
    boolean dueTick = false;
    for (JsonNode machine : machines) {
      UUID machineId = UUID.fromString(machine.path("machineId").asText());
      JsonNode machinePhase = null;
      for (JsonNode candidate : machine.path("phases")) if (phaseCode.equals(candidate.path("code").asText())) { machinePhase = candidate; break; }
      if (machinePhase == null) continue;
      List<JsonNode> signals = nodes(machinePhase.path("signals"));
      signals.sort(java.util.Comparator.comparing(signal -> signal.path("signalId").asText()));
      for (JsonNode signal : signals) {
        JsonNode behavior = signal.path("behavior");
        long interval = behavior.path("generationIntervalMs").asLong();
        if (interval < 1000) throw new IllegalArgumentException("Signal generation interval is invalid");
        long tickIndex = phaseElapsed / interval;
        String slotKey = claim.attemptNo() + ":" + machineId + ":" + signal.path("signalId").asText() + ":" + phaseCode + ":" + cycle;
        long previous = slots.path(slotKey).asLong(-1);
        if (tickIndex <= previous) continue;
        dueTick = true;
        long skipped = Math.max(0, tickIndex - previous - 1);
        Instant observedAt = phaseStart.plusMillis(tickIndex * interval);
        UUID signalId = UUID.fromString(signal.path("signalId").asText());
        RealtimeBehaviorEvaluator.Result generated = evaluator.evaluate(behavior,
            signal.path("definition").path("value_type").asText(), claim.randomSeed(), machineId, signalId,
            phaseCode, cycle, tickIndex, tickIndex * interval);
        slots.put(slotKey, tickIndex);
        dropped += skipped;
        through = through.isAfter(observedAt) ? through : observedAt;
        if (generated.missing()) continue;
        generatedCount++;
        if (measurements.size() >= MAX_BATCH) { dropped++; continue; }
        measurements.add(new TelemetryMeasurement(signalId, observedAt, generated.value(), generated.quality()));
      }
    }
    if (!dueTick) return null;
    return new Draft(measurements, resultCheckpoint, through, phaseCode, cycle, generatedCount, dropped);
  }

  private JsonNode encode(TelemetryMeasurement measurement) {
    ObjectNode row = mapper.createObjectNode();
    row.put("signalId", measurement.signalId().toString());
    row.put("observedAt", measurement.observedAt().toEpochMilli());
    row.put("quality", measurement.sourceQuality());
    Object value = measurement.value();
    if (value instanceof Double number) { row.put("type", "DOUBLE"); row.put("value", number); }
    else if (value instanceof Long number) { row.put("type", "LONG"); row.put("value", number); }
    else if (value instanceof Boolean bool) { row.put("type", "BOOLEAN"); row.put("value", bool); }
    else { row.put("type", "STRING"); row.put("value", value.toString()); }
    return row;
  }

  private TelemetryMeasurement decode(JsonNode row) {
    Object value = switch (row.path("type").asText()) {
      case "DOUBLE" -> row.path("value").asDouble();
      case "LONG" -> row.path("value").asLong();
      case "BOOLEAN" -> row.path("value").asBoolean();
      case "STRING" -> row.path("value").asText();
      default -> throw new IllegalArgumentException("Stored pending measurement has invalid type");
    };
    return new TelemetryMeasurement(UUID.fromString(row.path("signalId").asText()),
        Instant.ofEpochMilli(row.path("observedAt").asLong()), value, (short) row.path("quality").asInt());
  }

  private List<JsonNode> nodes(JsonNode array) {
    List<JsonNode> rows = new ArrayList<>();
    array.forEach(rows::add);
    return rows;
  }
  private String safeMessage(Throwable error) {
    String message = error.getMessage() == null ? "Realtime execution failed" : error.getMessage();
    return message.length() <= 500 ? message : message.substring(0, 500);
  }
  private record Draft(List<TelemetryMeasurement> measurements, JsonNode checkpoint, Instant through,
      String phase, long cycle, long generated, long dropped) {}
}
