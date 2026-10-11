package ru.fkit.bi.generator.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transport and domain value types for the replaceable Scenario definition. */
public final class ScenarioModels {
  private ScenarioModels() {}

  public enum CycleMode { ONCE, LOOP }
  public enum Status { ACTIVE, DISABLED }
  public enum Severity { ERROR, WARNING }
  public enum ExpectedState { RUN, IDLE, DOWN, SETUP, PLANNED_STOP, OFFLINE }
  public enum SourceQuality { GOOD, UNCERTAIN, BAD }
  public enum OverrideAction { REPLACE, DISABLE }
  public enum BehaviorModel { CONSTANT, UNIFORM, NORMAL, RAMP, SINE, COUNTER_RATE, PULSE, BERNOULLI, SEQUENCE }

  public record CreateRequest(@NotNull UUID enterpriseId, String code, String name, String description,
      @NotNull CycleMode cycleMode, List<MachineTarget> machineTargets, List<Phase> phases) {}
  public record UpdateRequest(String name, String description, @NotNull CycleMode cycleMode,
      List<MachineTarget> machineTargets, List<Phase> phases) {}
  public record MachineTarget(@NotNull UUID machineId, List<BaseBehavior> baseBehaviors) {}
  public record BaseBehavior(@NotNull UUID signalId, @NotNull JsonNode behavior) {}
  public record Phase(String code, String name, Long durationMs, ExpectedState expectedState,
      List<BehaviorOverride> behaviorOverrides) {}
  public record BehaviorOverride(@NotNull UUID signalId, @NotNull OverrideAction action, JsonNode behavior) {}
  /** The discriminator is constrained to the supported model catalog; model-specific fields stay flat per OpenAPI. */
  public record BehaviorConfiguration(@NotNull BehaviorModel model, Long generationIntervalMs,
      SourceQuality sourceQuality, Double missingProbability, Long seedOverride, JsonNode fields) {}

  public record ValidationIssue(Severity severity, String code, String path, String message,
      String entityType, UUID entityId) {}
  public record ValidationResult(boolean valid, int errorCount, int warningCount, List<ValidationIssue> issues) {
    public ValidationResult { issues = List.copyOf(issues); }
  }
  public record Detail(UUID id, UUID enterpriseId, String code, String name, String description, Status status,
      CycleMode cycleMode, List<MachineTarget> machineTargets, List<Phase> phases, ValidationResult validation,
      Instant createdAt, Instant updatedAt) {}
  public record Summary(UUID id, UUID enterpriseId, String code, String name, Status status, CycleMode cycleMode,
      int machineCount, int phaseCount, Instant createdAt, Instant updatedAt) {}
  public record Page(List<Summary> items, int page, int size, long totalElements, int totalPages) {
    public Page { items = List.copyOf(items); }
  }
  public record StatusRequest(@NotNull Status status) {}
}
