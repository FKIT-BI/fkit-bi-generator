package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.fkit.bi.generator.run.RunModels.*;
import ru.fkit.bi.generator.scenario.ScenarioModels.BaseBehavior;
import ru.fkit.bi.generator.scenario.ScenarioModels.BehaviorOverride;
import ru.fkit.bi.generator.scenario.ScenarioModels.MachineTarget;
import ru.fkit.bi.generator.scenario.ScenarioModels.OverrideAction;
import ru.fkit.bi.generator.scenario.ScenarioModels.Phase;
import ru.fkit.bi.generator.scenario.ScenarioModels.Severity;
import ru.fkit.bi.generator.scenario.ScenarioService;

@Service
public class RunService {
  private static final Instant MAX_DATABASE_TIMESTAMP = Instant.parse("+294000-01-01T00:00:00Z");
  private static final SecureRandom RANDOM = new SecureRandom();
  private final RunRepository repository;
  private final ScenarioService scenarioService;
  private final RunSnapshotCompiler snapshotCompiler;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;

  public RunService(RunRepository repository, ScenarioService scenarioService,
      RunSnapshotCompiler snapshotCompiler, ObjectMapper mapper,
      PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
    this.repository = repository;
    this.scenarioService = scenarioService;
    this.snapshotCompiler = snapshotCompiler;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.jdbc = jdbc;
  }

  public ValidationResult validate(CreateRequest request) {
    Instant now = repository.databaseNow();
    var scenario = getScenario(request);
    ValidationResult result = validateRequest(request, scenario, now);
    return result;
  }

  public Detail create(String principalId, String idempotencyKey, CreateRequest request) {
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid");
    byte[] requestHash = requestHash(request);
    try {
      return transactions.execute(status -> createInTransaction(principalId, idempotencyKey, request, requestHash));
    } catch (DataIntegrityViolationException exception) {
      throw new RunConflictException("RUN_RESERVATION_CONFLICT", "Run conflicts with an existing reservation", Map.of());
    }
  }

  private Detail createInTransaction(String principalId, String key, CreateRequest request, byte[] requestHash) {
    repository.lockIdempotencyKey(principalId, key);
    var previous = repository.getIdempotency(principalId, key);
    if (previous.isPresent()) {
      if (!MessageDigest.isEqual(previous.get().requestHash(), requestHash))
        throw new RunConflictException("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request", Map.of());
      return get(previous.get().runId());
    }
    if (!repository.lockScenario(request.scenarioId()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario not found");
    var scenario = getScenario(request);
    if (scenario.status() != ru.fkit.bi.generator.scenario.ScenarioModels.Status.ACTIVE)
      throw new RunConflictException("SCENARIO_NOT_ACTIVE", "Scenario must be active before a Run can be created", Map.of("scenarioId", scenario.id()));
    Instant now = repository.databaseNow();
    ValidationResult validation = validateRequest(request, scenario, now);
    if (!validation.valid()) {
      if (!validation.conflicts().isEmpty()) throw conflict(validation.conflicts());
      throw new RunValidationException(validation);
    }
    List<UUID> machineIds = scenario.machineTargets().stream().map(MachineTarget::machineId).distinct().sorted().toList();
    for (UUID machineId : machineIds) {
      if (!repository.tryLockMachine(machineId))
        throw new RunConflictException("RUN_RESERVATION_BUSY", "Could not reserve all target machines immediately", Map.of("machineIds", machineIds));
    }
    if (request.mode() == Mode.HISTORICAL) {
      if (!repository.tryLockHistoricalCapacity())
        throw new RunConflictException("HISTORICAL_CAPACITY_BUSY", "Historical execution capacity is already reserved", Map.of());
      var capacityOwner = repository.historicalCapacityOwner();
      if (capacityOwner.isPresent())
        throw new RunConflictException("HISTORICAL_CAPACITY_BUSY", "Historical execution capacity is already reserved", Map.of("conflictingRunId", capacityOwner.get()));
    }
    Instant[] reservation = reservationInterval(request, scenario, now);
    List<Conflict> reservations = repository.reservationConflicts(machineIds, reservation[0], reservation[1]);
    if (!reservations.isEmpty()) throw conflict(reservations);
    if (request.mode() == Mode.HISTORICAL) {
      List<Conflict> coverage = repository.coverageConflicts(machineIds, request.from(), request.to());
      if (!coverage.isEmpty()) throw conflict(coverage);
    }
    long seed = request.randomSeed() == null ? RANDOM.nextLong() : request.randomSeed();
    JsonNode snapshot = snapshotCompiler.compile(scenario, request, seed);
    Instant eventFrom = request.mode() == Mode.HISTORICAL ? request.from()
        : request.mode() == Mode.SCHEDULED ? request.scheduledStartAt() : null;
    Instant eventTo = request.mode() == Mode.HISTORICAL ? request.to()
        : request.mode() == Mode.SCHEDULED ? plannedScheduledEnd(request, scenario) : null;
    repository.create(principalId, key, requestHash, request, scenario, snapshot, seed, eventFrom,
        eventTo, reservation[0], reservation[1], now);
    return getLatestForKey(principalId, key);
  }

  public Page list(UUID enterpriseId, UUID scenarioId, UUID machineId, Mode mode, List<Status> statuses,
      String requestedFromText, String requestedToText, String cursorText, Integer requestedLimit) {
    if (enterpriseId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enterpriseId is required");
    int limit = requestedLimit == null ? 100 : requestedLimit;
    if (limit < 1 || limit > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 500");
    Instant requestedFrom = parseInstant(requestedFromText, "requestedFrom");
    Instant requestedTo = parseInstant(requestedToText, "requestedTo");
    if (requestedFrom != null && requestedTo != null && !requestedTo.isAfter(requestedFrom))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestedTo must be after requestedFrom");
    List<Status> uniqueStatuses = statuses == null ? List.of() : statuses.stream().distinct().toList();
    if (statuses != null && (statuses.size() > 8 || uniqueStatuses.size() != statuses.size()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status filters must be unique and contain at most eight values");
    RunRepository.Cursor cursor = null;
    if (cursorText != null) {
      if (cursorText.isBlank() || cursorText.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid");
      try { cursor = RunRepository.decodeCursor(cursorText); }
      catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid"); }
    }
    List<Summary> found = repository.list(enterpriseId, scenarioId, machineId, mode, uniqueStatuses,
        requestedFrom, requestedTo, cursor, limit);
    boolean more = found.size() > limit;
    List<Summary> items = more ? found.subList(0, limit) : found;
    String next = more && !items.isEmpty()
        ? RunRepository.encodeCursor(items.getLast().requestedAt(), items.getLast().id()) : null;
    return new Page(items, next);
  }

  public Detail get(UUID runId) {
    return repository.get(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
  }

  public StopResult stop(UUID runId) {
    Status status = repository.stop(runId, repository.databaseNow());
    Detail detail = get(runId);
    return new StopResult(detail, status == Status.STOP_REQUESTED ? 202 : 200);
  }

  public Detail retry(UUID runId) {
    int outcome = repository.retry(runId, repository.databaseNow());
    if (outcome == -1) throw new RunConflictException("RUN_STATE_CONFLICT", "Only retryable failed Runs may be retried", Map.of("runId", runId));
    if (outcome == -2) throw new RunConflictException("RUN_RESERVATION_BUSY", "Could not reserve all target machines immediately", Map.of("runId", runId));
    if (outcome == -3) throw new RunConflictException("RUN_RESERVATION_CONFLICT", "Run conflicts with an existing reservation", Map.of("runId", runId));
    if (outcome == -4) throw new RunConflictException("HISTORICAL_CAPACITY_BUSY", "Historical execution capacity is already reserved", Map.of("runId", runId));
    return get(runId);
  }

  private ru.fkit.bi.generator.scenario.ScenarioModels.Detail getScenario(CreateRequest request) {
    if (request == null || request.scenarioId() == null)
      throw new RunValidationException(validationWithIssue(request == null ? null : request.mode(), "SCENARIO_REQUIRED", "scenarioId", "Scenario is required"));
    return scenarioService.get(request.scenarioId());
  }

  private ValidationResult validateRequest(CreateRequest request, ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario, Instant now) {
    List<ValidationIssue> issues = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();
    if (request.mode() == null) issue(issues, "MODE_REQUIRED", "mode", "Run mode is required");
    if (request.scenarioId() == null) issue(issues, "SCENARIO_REQUIRED", "scenarioId", "Scenario is required");
    if (request.mode() != null) {
      switch (request.mode()) {
        case REALTIME -> {
          if (request.durationMs() != null && request.durationMs() < 1000) issue(issues, "INVALID_DURATION", "durationMs", "durationMs must be at least 1000");
          if (request.durationMs() != null && request.durationMs() >= 1000 && !canAddMillis(now, request.durationMs()))
            issue(issues, "INVALID_DURATION", "durationMs", "durationMs exceeds the supported timestamp range");
          if (request.from() != null || request.to() != null || request.scheduledStartAt() != null || request.eventTo() != null)
            issue(issues, "MODE_FIELD_NOT_ALLOWED", "mode", "Realtime request contains fields for another run mode");
        }
        case HISTORICAL -> {
          if (request.from() == null) issue(issues, "FROM_REQUIRED", "from", "Historical from is required");
          if (request.to() == null) issue(issues, "TO_REQUIRED", "to", "Historical to is required");
          if (request.from() != null && request.to() != null) {
            if (!request.to().isAfter(request.from())) issue(issues, "INVALID_RANGE", "to", "to must be after from");
            if (Duration.between(request.from(), request.to()).compareTo(Duration.ofDays(90)) > 0)
              issue(issues, "HISTORICAL_RANGE_TOO_LONG", "to", "Historical range cannot exceed 90 days");
            if (request.to().isAfter(now)) issue(issues, "HISTORICAL_RANGE_IN_FUTURE", "to", "Historical range must end no later than database time");
            if (request.from().isBefore(now.minus(Duration.ofDays(90))))
              issue(issues, "HISTORICAL_RANGE_OUTSIDE_RETENTION", "from", "Historical range must be inside the active raw retention window");
            if (request.from().isBefore(request.to()) && !request.to().isAfter(now)) {
              List<Instant> missingDays = repository.missingHistoricalPartitions(request.from(), request.to());
              if (!missingDays.isEmpty()) issue(issues, "RAW_PARTITIONS_NOT_READY", "from", "Raw partitions are not active for the requested historical range");
            }
          }
          if (request.durationMs() != null || request.scheduledStartAt() != null || request.eventTo() != null)
            issue(issues, "MODE_FIELD_NOT_ALLOWED", "mode", "Historical request contains fields for another run mode");
        }
        case SCHEDULED -> {
          if (request.scheduledStartAt() == null) issue(issues, "SCHEDULED_START_REQUIRED", "scheduledStartAt", "scheduledStartAt is required");
          else if (!request.scheduledStartAt().isAfter(now)) issue(issues, "SCHEDULED_START_NOT_FUTURE", "scheduledStartAt", "scheduledStartAt must be in the future");
          if (request.scheduledStartAt() != null && !request.scheduledStartAt().isBefore(MAX_DATABASE_TIMESTAMP))
            issue(issues, "INVALID_SCHEDULED_START", "scheduledStartAt", "scheduledStartAt exceeds the supported timestamp range");
          if (request.eventTo() != null && !request.eventTo().isBefore(MAX_DATABASE_TIMESTAMP))
            issue(issues, "INVALID_RANGE", "eventTo", "eventTo exceeds the supported timestamp range");
          if (request.durationMs() != null && request.durationMs() < 1000) issue(issues, "INVALID_DURATION", "durationMs", "durationMs must be at least 1000");
          if (request.durationMs() != null && request.durationMs() >= 1000 && request.scheduledStartAt() != null
              && !canAddMillis(request.scheduledStartAt(), request.durationMs()))
            issue(issues, "INVALID_DURATION", "durationMs", "durationMs exceeds the supported timestamp range");
          if (request.durationMs() != null && request.eventTo() != null) issue(issues, "SCHEDULED_BOUNDARY_CONFLICT", "eventTo", "Specify at most one of durationMs and eventTo");
          if (request.eventTo() != null && request.scheduledStartAt() != null && !request.eventTo().isAfter(request.scheduledStartAt()))
            issue(issues, "INVALID_RANGE", "eventTo", "eventTo must be after scheduledStartAt");
          if (request.from() != null || request.to() != null) issue(issues, "MODE_FIELD_NOT_ALLOWED", "mode", "Scheduled request contains historical fields");
        }
      }
    }
    if (scenario != null) {
      for (var scenarioIssue : scenario.validation().issues()) {
        issues.add(new ValidationIssue(scenarioIssue.severity().name(), scenarioIssue.code(), "scenario." + scenarioIssue.path(),
            scenarioIssue.message(), scenarioIssue.entityType(), scenarioIssue.entityId()));
      }
      if (scenario.status() != ru.fkit.bi.generator.scenario.ScenarioModels.Status.ACTIVE)
        issue(issues, "SCENARIO_NOT_ACTIVE", "scenarioId", "Scenario must be active before it can be run");
    }
    if (request.mode() != null && scenario != null && issues.stream().noneMatch(i -> "ERROR".equals(i.severity()))) {
      Instant[] interval = reservationInterval(request, scenario, now);
      List<UUID> machines = scenario.machineTargets().stream().map(MachineTarget::machineId).distinct().sorted().toList();
      conflicts.addAll(repository.reservationConflicts(machines, interval[0], interval[1]));
      if (request.mode() == Mode.HISTORICAL) {
        conflicts.addAll(repository.coverageConflicts(machines, request.from(), request.to()));
        repository.historicalCapacityOwner().ifPresent(owner -> conflicts.add(new Conflict("HISTORICAL_CAPACITY_BUSY", machines, null, null, owner)));
      }
    }
    Instant normalizedFrom = switch (request.mode() == null ? Mode.REALTIME : request.mode()) {
      case HISTORICAL -> request.from();
      case SCHEDULED -> request.scheduledStartAt();
      case REALTIME -> null;
    };
    Instant normalizedTo = switch (request.mode() == null ? Mode.REALTIME : request.mode()) {
      case HISTORICAL -> request.to();
      case SCHEDULED -> plannedScheduledEnd(request, scenario);
      case REALTIME -> null;
    };
    boolean valid = issues.stream().noneMatch(i -> "ERROR".equals(i.severity())) && conflicts.isEmpty();
    Long estimate = !valid || scenario == null || !scenario.validation().valid() || request.mode() == null
        ? null : estimateMeasurements(request, scenario, normalizedFrom, normalizedTo);
    Long batches = estimate == null ? null : (estimate + 999L) / 1000L;
    Long seconds = !valid ? null : request.mode() == Mode.HISTORICAL && estimate != null
        ? Long.valueOf((estimate + 999L) / 1000L) : durationSeconds(request, scenario, normalizedFrom, normalizedTo);
    return new ValidationResult(valid, request.mode(), normalizedFrom, normalizedTo, estimate, batches, seconds, issues, conflicts);
  }

  private long effectiveCounts(ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario, long durationMs) {
    List<Phase> phases = scenario.phases();
    long sequenceDuration = snapshotCompiler.sequenceDurationMs(scenario);
    if (sequenceDuration <= 0 || durationMs <= 0) return 0L;
    long total = 0;
    for (MachineTarget target : scenario.machineTargets()) {
      long machineSequenceCount = 0;
      long machineTotal = 0;
      long machineRemaining = durationMs;
      Map<UUID, JsonNode> base = new LinkedHashMap<>();
      if (target.baseBehaviors() != null) for (BaseBehavior b : target.baseBehaviors()) if (b != null && b.behavior() != null) base.put(b.signalId(), b.behavior());
      for (Phase phase : phases) {
        Map<UUID, JsonNode> effective = new HashMap<>(base);
        if (phase.behaviorOverrides() != null) for (BehaviorOverride o : phase.behaviorOverrides()) {
          if (o.action() == OverrideAction.DISABLE) effective.remove(o.signalId());
          else if (o.action() == OverrideAction.REPLACE) effective.put(o.signalId(), o.behavior());
        }
        long phaseCount = 0;
        for (JsonNode behavior : effective.values()) {
          long interval = behavior.path("generationIntervalMs").asLong(1000);
          if (interval > 0) phaseCount += (phase.durationMs() + interval - 1) / interval;
        }
        machineSequenceCount += phaseCount;
        long part = Math.min(machineRemaining, phase.durationMs());
        machineTotal += estimatePhase(effective, part);
        machineRemaining -= part;
        if (machineRemaining <= 0) break;
      }
      // LOOP measurements repeat the phase sequence; ONCE never does.
      if (scenario.cycleMode() == ru.fkit.bi.generator.scenario.ScenarioModels.CycleMode.LOOP && machineRemaining > 0) {
        long fullCycles = machineRemaining / sequenceDuration;
        machineTotal += Math.multiplyExact(fullCycles, machineSequenceCount);
        machineRemaining -= fullCycles * sequenceDuration;
        if (machineRemaining > 0) for (Phase phase : phases) {
          long part = Math.min(machineRemaining, phase.durationMs());
          Map<UUID, JsonNode> effective = new LinkedHashMap<>();
          if (target.baseBehaviors() != null) for (BaseBehavior b : target.baseBehaviors()) if (b != null && b.behavior() != null) effective.put(b.signalId(), b.behavior());
          if (phase.behaviorOverrides() != null) for (BehaviorOverride o : phase.behaviorOverrides()) {
            if (o.action() == OverrideAction.DISABLE) effective.remove(o.signalId());
            else if (o.action() == OverrideAction.REPLACE) effective.put(o.signalId(), o.behavior());
          }
          machineTotal += estimatePhase(effective, part);
          machineRemaining -= part;
          if (machineRemaining <= 0) break;
        }
      }
      total = Math.addExact(total, machineTotal);
    }
    return total;
  }

  private long estimatePhase(Map<UUID, JsonNode> effective, long durationMs) {
    long count = 0;
    for (JsonNode behavior : effective.values()) {
      long interval = behavior.path("generationIntervalMs").asLong(1000);
      if (interval > 0 && durationMs > 0) count += (durationMs + interval - 1) / interval;
    }
    return count;
  }

  private Long estimateMeasurements(CreateRequest request, ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario, Instant from, Instant to) {
    long durationMs = 0;
    if (request.mode() == Mode.HISTORICAL && from != null && to != null) durationMs = Duration.between(from, to).toMillis();
    else if (request.mode() == Mode.REALTIME) {
      if (request.durationMs() != null) durationMs = request.durationMs();
      else if (scenario.cycleMode() == ru.fkit.bi.generator.scenario.ScenarioModels.CycleMode.ONCE) durationMs = snapshotCompiler.sequenceDurationMs(scenario);
      else return null;
    } else if (request.mode() == Mode.SCHEDULED) {
      if (request.durationMs() != null) durationMs = request.durationMs();
      else if (request.eventTo() != null && request.scheduledStartAt() != null) durationMs = Duration.between(request.scheduledStartAt(), request.eventTo()).toMillis();
      else if (scenario.cycleMode() == ru.fkit.bi.generator.scenario.ScenarioModels.CycleMode.ONCE) durationMs = snapshotCompiler.sequenceDurationMs(scenario);
      else return null;
    }
    if (durationMs <= 0) return 0L;
    return effectiveCounts(scenario, durationMs);
  }

  private Long durationSeconds(CreateRequest request, ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario, Instant from, Instant to) {
    if (request.mode() == Mode.HISTORICAL && from != null && to != null) return (Duration.between(from, to).toMillis() + 999) / 1000;
    if (request.durationMs() != null) return (request.durationMs() + 999) / 1000;
    if (request.mode() == Mode.SCHEDULED && request.eventTo() != null && request.scheduledStartAt() != null)
      return (Duration.between(request.scheduledStartAt(), request.eventTo()).toMillis() + 999) / 1000;
    if (scenario != null && scenario.cycleMode() == ru.fkit.bi.generator.scenario.ScenarioModels.CycleMode.ONCE)
      return (snapshotCompiler.sequenceDurationMs(scenario) + 999) / 1000;
    return null;
  }

  private Instant[] reservationInterval(CreateRequest request, ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario, Instant now) {
    if (request.mode() == Mode.HISTORICAL) return new Instant[]{request.from(), request.to()};
    if (request.mode() == Mode.SCHEDULED) return new Instant[]{request.scheduledStartAt(), plannedScheduledEnd(request, scenario)};
    long duration = request.durationMs() == null ? 0 : request.durationMs();
    if (duration == 0 && scenario.cycleMode() == ru.fkit.bi.generator.scenario.ScenarioModels.CycleMode.ONCE)
      duration = snapshotCompiler.sequenceDurationMs(scenario);
    return new Instant[]{now, duration > 0 ? now.plusMillis(duration) : null};
  }

  private Instant plannedScheduledEnd(CreateRequest request, ru.fkit.bi.generator.scenario.ScenarioModels.Detail scenario) {
    if (request == null || request.mode() != Mode.SCHEDULED || request.scheduledStartAt() == null) return null;
    if (request.eventTo() != null) return request.eventTo();
    long duration = request.durationMs() == null ? 0 : request.durationMs();
    if (duration == 0 && scenario != null && scenario.cycleMode() == ru.fkit.bi.generator.scenario.ScenarioModels.CycleMode.ONCE)
      duration = snapshotCompiler.sequenceDurationMs(scenario);
    return duration > 0 && canAddMillis(request.scheduledStartAt(), duration)
        ? request.scheduledStartAt().plusMillis(duration) : null;
  }

  private boolean canAddMillis(Instant start, long millis) {
    try { return start.plusMillis(millis).isBefore(MAX_DATABASE_TIMESTAMP); }
    catch (ArithmeticException | java.time.DateTimeException overflow) { return false; }
  }

  private RunConflictException conflict(List<Conflict> conflicts) {
    return new RunConflictException(conflicts.getFirst().code(), "Run conflicts with an existing reservation",
        Map.of("conflicts", conflicts));
  }

  private ValidationResult validationWithIssue(Mode mode, String code, String path, String message) {
    return new ValidationResult(false, mode, null, null, null, null, null,
        List.of(new ValidationIssue("ERROR", code, path, message, null, null)), List.of());
  }

  private void issue(List<ValidationIssue> issues, String code, String path, String message) {
    issues.add(new ValidationIssue("ERROR", code, path, message, null, null));
  }

  private Instant parseInstant(String value, String field) {
    if (value == null) return null;
    try { return java.time.OffsetDateTime.parse(value).toInstant(); }
    catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be an ISO timestamp with offset"); }
  }

  private byte[] requestHash(CreateRequest request) {
    try {
      JsonNode tree = mapper.valueToTree(request);
      return MessageDigest.getInstance("SHA-256").digest(canonical(tree).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception exception) { throw new IllegalStateException("Cannot hash Run request", exception); }
  }

  private String canonical(JsonNode node) {
    if (node == null || node.isNull()) return "null";
    if (node.isObject()) {
      List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); names.sort(Comparator.naturalOrder());
      List<String> fields = new ArrayList<>();
      for (String name : names) fields.add(mapper.valueToTree(name).toString() + ":" + canonical(node.get(name)));
      return "{" + String.join(",", fields) + "}";
    }
    if (node.isArray()) { List<String> values = new ArrayList<>(); node.forEach(value -> values.add(canonical(value))); return "[" + String.join(",", values) + "]"; }
    return node.toString();
  }

  private Detail getLatestForKey(String principalId, String key) {
    UUID id = jdbc.queryForObject("SELECT run_id FROM generator.generation_run_idempotency WHERE principal_id=? AND idempotency_key=?", UUID.class, principalId, key);
    return get(id);
  }

  public record StopResult(Detail detail, int httpStatus) {}
}
