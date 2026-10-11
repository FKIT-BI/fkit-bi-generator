package ru.fkit.bi.generator.run;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RunModels {
  private RunModels() {}

  public enum Mode { REALTIME, HISTORICAL, SCHEDULED }
  public enum Status { SCHEDULED, QUEUED, RUNNING, STOP_REQUESTED, COMPLETED, STOPPED, CANCELLED, FAILED }

  public record CreateRequest(
      @NotNull Mode mode,
      @NotNull UUID scenarioId,
      Long randomSeed,
      Long durationMs,
      Instant from,
      Instant to,
      Instant scheduledStartAt,
      Instant eventTo) {}

  public record ValidationIssue(String severity, String code, String path, String message,
      String entityType, UUID entityId) {}
  public record Conflict(String code, List<UUID> machineIds, Instant from, Instant to, UUID conflictingRunId) {
    public Conflict { machineIds = List.copyOf(machineIds); }
  }
  public record ValidationResult(boolean valid, Mode mode, Instant normalizedFrom, Instant normalizedTo,
      Long estimatedMeasurements, Long estimatedBatches, Long estimatedWallDurationSeconds,
      List<ValidationIssue> issues, List<Conflict> conflicts) {
    public ValidationResult { issues = List.copyOf(issues); conflicts = List.copyOf(conflicts); }
  }
  public record Progress(String currentPhaseCode, long cycleNumber, Instant generatedThrough,
      Double progressPercent, long generatedMeasurements, long writtenMeasurements,
      long droppedMeasurements, long failedBatches, Instant lastSuccessfulWriteAt) {}
  public record Failure(String code, String message, boolean retryable, Instant failedAt) {}
  public record AnalyticsContext(UUID enterpriseId, List<UUID> machineIds, Instant from, Instant to,
      Instant generatedThrough) {
    public AnalyticsContext { machineIds = List.copyOf(machineIds); }
  }
  public record Checkpoint(Instant completedThrough, String phaseCode, long cycleNumber, long batchSequence) {}
  public record Summary(UUID id, UUID scenarioId, UUID enterpriseId, Mode mode, Status status,
      int attemptNo, Instant requestedAt, Instant scheduledStartAt, Instant startedAt,
      Instant finishedAt, int machineCount, Progress progress) {}
  public record Detail(UUID id, UUID scenarioId, UUID enterpriseId, Mode mode, Status status,
      int attemptNo, long randomSeed, Instant requestedAt, Instant scheduledStartAt,
      Instant startedAt, Instant finishedAt, Instant stopRequestedAt, Instant eventFrom,
      Instant eventTo, List<UUID> machineIds, Progress progress, Checkpoint checkpoint,
      Failure failure, AnalyticsContext analyticsContext) {
    public Detail { machineIds = List.copyOf(machineIds); }
  }
  public record Page(List<Summary> items, String nextCursor) {
    public Page { items = List.copyOf(items); }
  }
}
