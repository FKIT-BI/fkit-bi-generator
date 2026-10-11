package ru.fkit.bi.generator.epic6;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.Contexts;
import liquibase.LabelExpression;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.fkit.bi.generator.run.ScheduledRunStarter;
import ru.fkit.bi.generator.run.RealtimeRunExecutor;
import ru.fkit.bi.generator.run.RealtimeRunLifecycleRepository;
import ru.fkit.bi.generator.run.HistoricalRunExecutor;
import ru.fkit.bi.generator.run.RealtimeRunExecutor;
import ru.fkit.bi.generator.telemetry.RealtimeTelemetryExecutionService;
import ru.fkit.bi.generator.telemetry.TelemetryBatch;
import ru.fkit.bi.generator.telemetry.TelemetryMeasurement;

/** Real PostgreSQL/API coverage for the Scenario and Run contract edges. */
@Testcontainers
@SpringBootTest(properties = {
    "generator.realtime.dispatch-delay-ms=3600000",
    "generator.historical.dispatch-delay-ms=3600000",
    "generator.scheduled.poll-delay-ms=3600000",
    "generator.realtime.initial-delay-ms=3600000",
    "generator.historical.initial-delay-ms=3600000",
    "generator.scheduled.initial-delay-ms=3600000",
    "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
class GeneratorEpic6ApiAcceptanceTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  private static boolean migrated;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    if (!migrated) {
      migrateAnalyticsSchema();
      migrated = true;
    }
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("fkit.auth.jwt-secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
  }

  private static void migrateAnalyticsSchema() {
    Path root = analyticsChangelogRoot();
    try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      var accessor = new DirectoryResourceAccessor(root);
      var liquibase = new Liquibase("db.changelog-master.yaml", accessor, database);
      liquibase.update(new Contexts(), new LabelExpression());
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static Path analyticsChangelogRoot() {
    Path checkout = Path.of(".ci/fkit-bi-analytics/src/main/resources/db/changelog");
    if (java.nio.file.Files.isDirectory(checkout)) return checkout.toAbsolutePath().normalize();
    return Path.of("../fkit-bi-analytics/src/main/resources/db/changelog").toAbsolutePath().normalize();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired ScheduledRunStarter scheduledRunStarter;
  @Autowired RealtimeRunExecutor realtimeExecutor;
  @Autowired RealtimeRunLifecycleRepository runLifecycle;
  @Autowired HistoricalRunExecutor historicalExecutor;
  @Autowired RealtimeRunExecutor realtimeRunExecutor;
  @Autowired RealtimeTelemetryExecutionService telemetryExecution;

  @Test
  void incompleteBehaviorIsSavedDisabledButCannotBeActivated() throws Exception {
    Fixture fixture = fixture(1);
    Map<String, Object> body = scenarioBody(fixture.enterprise(), List.of(fixture.machines().getFirst()),
        fixture.signals().subList(0, 1), false);
    String response = mvc.perform(post("/api/v1/scenarios").with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DISABLED"))
        .andExpect(jsonPath("$.validation.valid").value(false)).andReturn().getResponse().getContentAsString();
    String scenarioId = mapper.readTree(response).path("id").asText();

    mvc.perform(put("/api/v1/scenarios/{id}/status", scenarioId).with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SCENARIO_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("machineTargets[0].baseBehaviors[0].behavior.value"));
    assertEquals("DISABLED", jdbc.queryForObject("SELECT status FROM generator.generation_scenario WHERE id=?", String.class, UUID.fromString(scenarioId)));
  }

  @Test
  void sameIdempotencyKeyReturnsOriginalRunAndDifferentBodyConflicts() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    Map<String, Object> run = realtimeRequest(scenarioId, 12_000L, 41L);
    String first = createRun(run, "stable-key-0001");
    String replay = createRun(run, "stable-key-0001");
    assertEquals(mapper.readTree(first).path("id").asText(), mapper.readTree(replay).path("id").asText());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM generator.generation_run WHERE scenario_id=?", Integer.class,
        UUID.fromString(scenarioId)));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM generator.generation_run_reservation WHERE run_id IN "
        + "(SELECT id FROM generator.generation_run WHERE scenario_id=?)", Integer.class, UUID.fromString(scenarioId)));

    Map<String, Object> changed = realtimeRequest(scenarioId, 12_000L, 42L);
    mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", "stable-key-0001")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(changed)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM generator.generation_run WHERE scenario_id=?", Integer.class,
        UUID.fromString(scenarioId)));
  }

  @Test
  void realtimeNoCatchupWritesThroughSinkIntoAnalyticsCurrentProjection() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    Map<String, Object> request = realtimeRequest(scenarioId, 1_000L, 731L);
    String created = createRun(request, "realtime-golden-0001");
    UUID runId = UUID.fromString(mapper.readTree(created).path("id").asText());

    Instant delayedAttemptStart = Instant.now().minusSeconds(5);
    jdbc.update("UPDATE generator.generation_run_attempt SET started_at=? WHERE run_id=? AND attempt_no=1",
        Timestamp.from(delayedAttemptStart), runId);
    var claim = runLifecycle.claim("epic6-acceptance-worker", runLifecycle.databaseNow()).orElseThrow();
    realtimeExecutor.execute(claim);

    assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM generator.generation_run WHERE id=?", String.class, runId),
        jdbc.queryForMap("SELECT failure_code,failure_message FROM generator.generation_run WHERE id=?", runId).toString());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM telemetry.ingest_batch WHERE generator_run_id=?", Integer.class, runId));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement WHERE ingest_batch_id IN "
        + "(SELECT id FROM telemetry.ingest_batch WHERE generator_run_id=?)", Integer.class, runId));
    assertEquals(5, jdbc.queryForObject("SELECT dropped_measurements FROM generator.generation_run WHERE id=?", Long.class, runId));

    jdbc.queryForObject("SELECT analytics.refresh_signal_current_for_machine(?,clock_timestamp())", Integer.class,
        fixture.machines().getFirst());
    assertEquals(17.25, jdbc.queryForObject("SELECT usable_double FROM analytics.signal_current_value WHERE signal_id=?",
        Double.class, fixture.signals().getFirst()));
  }

  @Test
  void historicalRunWritesStableBatchesAndCommitsCoverage() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    Instant to = runLifecycle.databaseNow().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant from = to.minusSeconds(10);
    Map<String, Object> request = Map.of("scenarioId", scenarioId, "mode", "HISTORICAL", "from", from,
        "to", to, "randomSeed", 617L);
    String created = mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", "historical-run-0001")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    UUID runId = UUID.fromString(mapper.readTree(created).path("id").asText());
    var claim = runLifecycle.claimHistorical("epic6-historical-worker", runLifecycle.databaseNow()).orElseThrow();
    assertEquals(runId, claim.id());
    assertNotNull(claim.eventFrom(), claim.toString());
    assertNotNull(claim.eventTo(), claim.toString());
    assertTrue(claim.eventTo().isAfter(claim.eventFrom()), claim.toString());
    historicalExecutor.execute(claim);

    assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM generator.generation_run WHERE id=?", String.class, runId),
        jdbc.queryForMap("SELECT failure_code,failure_message FROM generator.generation_run WHERE id=?", runId).toString());
    assertTrue(jdbc.queryForObject("SELECT count(*)>0 FROM telemetry.ingest_batch WHERE generator_run_id=?", Boolean.class, runId));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM generator.historical_coverage WHERE run_id=?", Integer.class, runId));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM generator.historical_capacity_slot WHERE run_id=?", Integer.class, runId));
  }

  @Test
  void historicalWorkerRestartReplaysPublishedPendingBatchWithoutDuplicatingRaw() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    Instant to = runLifecycle.databaseNow().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant from = to.minusSeconds(360);
    Map<String, Object> request = Map.of("scenarioId", scenarioId, "mode", "HISTORICAL", "from", from,
        "to", to, "randomSeed", 901L);
    String created = mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", "historical-crash-0001")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    UUID runId = UUID.fromString(mapper.readTree(created).path("id").asText());
    var original = runLifecycle.claimHistorical("historical-worker-before-crash", runLifecycle.databaseNow()).orElseThrow();

    String batchId = runId + ":hist:" + from.toEpochMilli() + ":0";
    List<TelemetryMeasurement> measurements = new ArrayList<>();
    for (int second = 0; second < 300; second++) {
      measurements.add(new TelemetryMeasurement(fixture.signals().getFirst(), from.plusSeconds(second), 17.25d));
    }
    ObjectNode checkpoint = mapper.createObjectNode();
    checkpoint.put("chunkFrom", from.toEpochMilli());
    ObjectNode pending = checkpoint.putObject("pendingBatch");
    pending.put("sequence", 1L);
    pending.put("batchId", batchId);
    pending.put("count", measurements.size());
    pending.put("maxObservedAt", measurements.getLast().observedAt().toEpochMilli());
    var rows = pending.putArray("measurements");
    for (TelemetryMeasurement measurement : measurements) {
      rows.addObject().put("signalId", measurement.signalId().toString())
          .put("observedAt", measurement.observedAt().toEpochMilli()).put("quality", measurement.sourceQuality())
          .put("type", "DOUBLE").put("value", 17.25d);
    }
    runLifecycle.historicalPending(runId, original.workerId(), 1L, checkpoint);

    // Model a worker crash after the sink committed but before checkpoint ACK.
    telemetryExecution.publish("fkit-generator", batchId, 1L, runId, TelemetryBatch.Mode.HISTORICAL, measurements);
    jdbc.update("UPDATE generator.generation_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", runId);
    var replacement = runLifecycle.claimHistorical("historical-worker-after-crash", runLifecycle.databaseNow()).orElseThrow();
    historicalExecutor.execute(replacement);

    assertEquals("COMPLETED", runLifecycle.state(runId).status().name());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM telemetry.ingest_batch WHERE producer_id='fkit-generator' AND producer_batch_id=?",
        Integer.class, batchId));
    assertEquals(300, jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement WHERE ingest_batch_id=("
        + "SELECT id FROM telemetry.ingest_batch WHERE producer_id='fkit-generator' AND producer_batch_id=?)", Integer.class, batchId));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM generator.historical_coverage WHERE run_id=?", Integer.class, runId));
    assertFalse(runLifecycle.checkpoint(runId).has("pendingBatch"));
  }

  @Test
  void phaseOverrideOnlyAppliesToTheMachineOwningItsSignal() throws Exception {
    Fixture fixture = fixture(2);
    Map<String, Object> scenario = new HashMap<>(scenarioBody(fixture.enterprise(), fixture.machines(), fixture.signals(), true));
    scenario.put("phases", List.of(Map.of("code", "RUN", "name", "Run", "durationMs", 60_000,
        "behaviorOverrides", List.of(Map.of("signalId", fixture.signals().getFirst(), "action", "REPLACE",
            "behavior", Map.of("model", "CONSTANT", "generationIntervalMs", 1000, "value", 33.0d))))));
    String created = mvc.perform(post("/api/v1/scenarios").with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(scenario)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String scenarioId = mapper.readTree(created).path("id").asText();
    mvc.perform(put("/api/v1/scenarios/{id}/status", scenarioId).with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk());
    String run = createRun(realtimeRequest(scenarioId, 5_000L, 901L), "override-machine-owner-01");
    JsonNode machines = mapper.readTree(jdbc.queryForObject("SELECT snapshot::text FROM generator.generation_run WHERE id=?",
        String.class, UUID.fromString(mapper.readTree(run).path("id").asText()))).path("machines");
    JsonNode secondMachine = null;
    for (JsonNode machine : machines) if (fixture.machines().get(1).toString().equals(machine.path("machineId").asText())) secondMachine = machine;
    assertNotNull(secondMachine);
    for (JsonNode signal : secondMachine.path("phases").get(0).path("signals"))
      assertNotEquals(fixture.signals().getFirst().toString(), signal.path("signalId").asText());
  }

  @Test
  void invalidCounterAndPulsePeriodsAreRejectedDuringScenarioValidation() throws Exception {
    Fixture fixture = fixture(1);
    Map<String, Object> counter = new HashMap<>(scenarioBody(fixture.enterprise(), List.of(fixture.machines().getFirst()),
        List.of(fixture.signals().getFirst()), true));
    counter.put("machineTargets", List.of(Map.of("machineId", fixture.machines().getFirst(), "baseBehaviors", List.of(
        Map.of("signalId", fixture.signals().getFirst(), "behavior", Map.of("model", "COUNTER_RATE",
            "generationIntervalMs", 1000, "initialValue", 0, "incrementPerSecond", 1,
            "resetThreshold", 0, "resetValue", 0))))));
    String counterResponse = mvc.perform(post("/api/v1/scenarios").with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(counter)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    assertTrue(mapper.readTree(counterResponse).path("validation").path("issues").toString().contains("INVALID_RESET_THRESHOLD"));

    Map<String, Object> pulse = new HashMap<>(scenarioBody(fixture.enterprise(), List.of(fixture.machines().getFirst()),
        List.of(fixture.signals().getFirst()), true));
    pulse.put("machineTargets", List.of(Map.of("machineId", fixture.machines().getFirst(), "baseBehaviors", List.of(
        Map.of("signalId", fixture.signals().getFirst(), "behavior", Map.of("model", "PULSE",
            "generationIntervalMs", 1000, "onDurationMs", Long.MAX_VALUE,
            "offDurationMs", Long.MAX_VALUE, "initialValue", true))))));
    String pulseResponse = mvc.perform(post("/api/v1/scenarios").with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(pulse)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    assertTrue(mapper.readTree(pulseResponse).path("validation").path("issues").toString().contains("INVALID_PULSE_PERIOD"));
  }

  @Test
  void unrepresentableRealtimeDurationReturnsStructuredValidationError() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    Map<String, Object> request = realtimeRequest(scenarioId, Long.MAX_VALUE, 901L);
    mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", "oversized-duration-0001")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("RUN_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("durationMs"));
  }

  @Test
  void runDetailListStopAndSnapshotRemainConsistent() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    String created = createRun(realtimeRequest(scenarioId, 5_000L, 88L), "run-reads-stop-0001");
    UUID runId = UUID.fromString(mapper.readTree(created).path("id").asText());
    mvc.perform(get("/api/v1/runs/{id}", runId).with(user("epic6")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.randomSeed").value(88));
    mvc.perform(get("/api/v1/runs").with(user("epic6"))
            .param("enterpriseId", fixture.enterprise().toString()).param("scenarioId", scenarioId))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(runId.toString()));
    jdbc.update("UPDATE generator.generation_scenario SET name='Edited after acceptance' WHERE id=?", UUID.fromString(scenarioId));
    assertEquals("Epic acceptance", jdbc.queryForObject("SELECT snapshot->>'scenarioName' FROM generator.generation_run WHERE id=?",
        String.class, runId));
    mvc.perform(post("/api/v1/runs/{id}/stop", runId).with(user("epic6")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM generator.generation_run_reservation WHERE run_id=? AND released_at IS NOT NULL",
        Integer.class, runId));
    assertNotNull(jdbc.queryForObject("SELECT finished_at FROM generator.generation_run_attempt WHERE run_id=? AND attempt_no=1",
        Timestamp.class, runId));
  }

  @Test
  void expiredWorkerLeaseIsRecoveredByReplacementWorker() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    String created = createRun(realtimeRequest(scenarioId, 30_000L, 91L), "worker-recovery-0001");
    UUID runId = UUID.fromString(mapper.readTree(created).path("id").asText());

    var original = runLifecycle.claim("worker-before-restart", runLifecycle.databaseNow()).orElseThrow();
    assertEquals(runId, original.id());
    assertEquals("worker-before-restart", runLifecycle.state(runId).leaseOwner());

    jdbc.update("UPDATE generator.generation_run SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", runId);
    var recovered = runLifecycle.claim("worker-after-restart", runLifecycle.databaseNow()).orElseThrow();

    assertEquals(runId, recovered.id());
    assertEquals("worker-after-restart", recovered.workerId());
    assertEquals("RUNNING", runLifecycle.state(runId).status().name());
    assertEquals("worker-after-restart", runLifecycle.state(runId).leaseOwner());
    assertEquals(original.attemptStartedAt(), recovered.attemptStartedAt(),
        "reclaim resumes the durable attempt rather than resetting run identity");
  }

  @Test
  void realtimePendingBatchIsWrittenBeforeAnExpiredRunCompletes() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    UUID runId = UUID.fromString(mapper.readTree(createRun(realtimeRequest(scenarioId, 5_000L, 92L),
        "realtime-pending-expired-0001")).path("id").asText());
    var claim = runLifecycle.claim("realtime-pending-worker", runLifecycle.databaseNow()).orElseThrow();
    TelemetryMeasurement measurement = new TelemetryMeasurement(fixture.signals().getFirst(), claim.attemptStartedAt().minusSeconds(5), 18.5d);
    ObjectNode checkpoint = mapper.createObjectNode();
    ObjectNode pending = checkpoint.putObject("pendingBatch");
    pending.put("sequence", 1L);
    pending.put("batchId", runId + ":rt:1");
    pending.put("phase", "RUN");
    pending.put("cycle", 0);
    pending.put("through", measurement.observedAt().toEpochMilli());
    pending.put("generated", 1);
    pending.put("dropped", 0);
    pending.putArray("measurements").addObject().put("signalId", measurement.signalId().toString())
        .put("observedAt", measurement.observedAt().toEpochMilli()).put("quality", measurement.sourceQuality())
        .put("type", "DOUBLE").put("value", 18.5d);
    runLifecycle.pending(runId, claim.workerId(), 1L, checkpoint, 1, 0);
    jdbc.update("UPDATE generator.generation_run SET event_from=?,event_to=? WHERE id=?",
        Timestamp.from(claim.attemptStartedAt().minusSeconds(10)), Timestamp.from(claim.attemptStartedAt().minusSeconds(1)), runId);

    realtimeRunExecutor.execute(claim);

    assertEquals("COMPLETED", runLifecycle.state(runId).status().name());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement r JOIN telemetry.ingest_batch b "
        + "ON b.id=r.ingest_batch_id WHERE b.generator_run_id=?", Integer.class, runId));
    assertFalse(runLifecycle.checkpoint(runId).has("pendingBatch"));
  }

  @Test
  void concurrentOverlappingMultiMachineCreatesLeaveNoPartialReservation() throws Exception {
    Fixture fixture = fixture(3);
    String firstScenario = createActiveScenario(fixture, List.of(0, 1));
    String secondScenario = createActiveScenario(fixture, List.of(1, 2));
    Map<String, Object> first = realtimeRequest(firstScenario, 15_000L, 11L);
    Map<String, Object> second = realtimeRequest(secondScenario, 15_000L, 12L);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Integer> a = pool.submit(() -> postRunAtBarrier(first, "race-key-0001", ready, start));
      Future<Integer> b = pool.submit(() -> postRunAtBarrier(second, "race-key-0002", ready, start));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      int statusA = a.get(20, TimeUnit.SECONDS);
      int statusB = b.get(20, TimeUnit.SECONDS);
      assertTrue((statusA == 202 && statusB == 409) || (statusA == 409 && statusB == 202), statusA + "/" + statusB);
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM generator.generation_run WHERE scenario_id IN (?,?)", Integer.class,
          UUID.fromString(firstScenario), UUID.fromString(secondScenario)));
      assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM generator.generation_run_reservation WHERE released_at IS NULL AND run_id IN "
          + "(SELECT id FROM generator.generation_run WHERE scenario_id IN (?,?))", Integer.class,
          UUID.fromString(firstScenario), UUID.fromString(secondScenario)));
      assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT machine_id) FROM generator.generation_run_reservation WHERE released_at IS NULL AND run_id IN "
          + "(SELECT id FROM generator.generation_run WHERE scenario_id IN (?,?))", Integer.class,
          UUID.fromString(firstScenario), UUID.fromString(secondScenario)));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void apiRequiresBearerAndReturnsTraceableStructuredError() throws Exception {
    mvc.perform(get("/api/v1/scenarios").param("enterpriseId", UUID.randomUUID().toString()).header("X-Trace-Id", "epic6-trace"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andExpect(jsonPath("$.traceId").value("epic6-trace"));
    mvc.perform(get("/api/v1/scenarios").param("enterpriseId", UUID.randomUUID().toString()).header("Authorization", "Bearer invalid.jwt.token"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andExpect(header().exists("X-Trace-Id"));
    mvc.perform(get("/api/v1/scenarios").with(user("epic6")).param("enterpriseId", "not-a-uuid"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists()).andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  void scheduledMisfireAfterThirtySecondsFailsWithoutRawTelemetry() throws Exception {
    Fixture fixture = fixture(1);
    String scenarioId = createActiveScenario(fixture, List.of(0));
    Map<String, Object> request = Map.of("scenarioId", scenarioId, "mode", "SCHEDULED",
        "scheduledStartAt", java.time.Instant.now().plusSeconds(90).toString(), "randomSeed", 71L);
    String created = mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", "scheduled-key-001")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
        .andExpect(result -> assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString()))
        .andReturn().getResponse().getContentAsString();
    UUID runId = UUID.fromString(mapper.readTree(created).path("id").asText());

    assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM generator.generation_run WHERE id=?", String.class, runId));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement WHERE signal_id=?", Integer.class,
        fixture.signals().getFirst()));

    jdbc.update("UPDATE generator.generation_run SET scheduled_start_at=clock_timestamp()-interval '31 seconds' WHERE id=?", runId);
    scheduledRunStarter.poll();

    assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM generator.generation_run WHERE id=?", String.class, runId));
    assertEquals("SCHEDULE_MISSED", jdbc.queryForObject("SELECT failure_code FROM generator.generation_run WHERE id=?", String.class, runId));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM telemetry.ingest_batch WHERE generator_run_id=?", Integer.class, runId));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM telemetry.raw_measurement WHERE signal_id=?", Integer.class,
        fixture.signals().getFirst()));
  }

  private int postRunAtBarrier(Map<String, Object> request, String key, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("race start timeout");
    return mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
        .andReturn().getResponse().getStatus();
  }

  private String createRun(Map<String, Object> request, String key) throws Exception {
    return mvc.perform(post("/api/v1/runs").with(user("epic6")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.randomSeed").exists())
        .andReturn().getResponse().getContentAsString();
  }

  private String createActiveScenario(Fixture fixture, List<Integer> machineIndexes) throws Exception {
    List<UUID> machines = machineIndexes.stream().map(fixture.machines()::get).toList();
    List<UUID> signals = machineIndexes.stream().map(fixture.signals()::get).toList();
    String response = mvc.perform(post("/api/v1/scenarios").with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(scenarioBody(fixture.enterprise(), machines, signals, true))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String id = mapper.readTree(response).path("id").asText();
    mvc.perform(put("/api/v1/scenarios/{id}/status", id).with(user("epic6"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    return id;
  }

  private Map<String, Object> scenarioBody(UUID enterprise, List<UUID> machines, List<UUID> signals, boolean complete) {
    List<Map<String, Object>> targets = new ArrayList<>();
    for (int i = 0; i < machines.size(); i++) {
      Map<String, Object> behavior = new LinkedHashMap<>();
      behavior.put("model", "CONSTANT");
      behavior.put("generationIntervalMs", 1000);
      if (complete) behavior.put("value", 17.25);
      targets.add(Map.of("machineId", machines.get(i), "baseBehaviors", List.of(Map.of("signalId", signals.get(i), "behavior", behavior))));
    }
    return Map.of("enterpriseId", enterprise, "code", "SCENARIO_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
        "name", "Epic acceptance", "cycleMode", "LOOP", "machineTargets", targets,
        "phases", List.of(Map.of("code", "RUN", "name", "Run", "durationMs", 60000, "behaviorOverrides", List.of())));
  }

  private Map<String, Object> realtimeRequest(String scenarioId, long durationMs, long seed) {
    return Map.of("scenarioId", scenarioId, "mode", "REALTIME", "durationMs", durationMs, "randomSeed", seed);
  }

  private Fixture fixture(int count) {
    UUID enterprise = UUID.randomUUID();
    jdbc.update("INSERT INTO equipment.enterprise(id,code,name,timezone,status) VALUES (?,?,?,'UTC','ACTIVE')",
        enterprise, code("ENT"), "Acceptance plant");
    UUID line = UUID.randomUUID();
    jdbc.update("INSERT INTO equipment.production_line(id,enterprise_id,code,name,sort_order,status) VALUES (?,?,?,?,0,'ACTIVE')",
        line, enterprise, code("LINE"), "Acceptance line");
    List<UUID> machines = new ArrayList<>();
    List<UUID> signals = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      UUID machine = UUID.randomUUID();
      jdbc.update("INSERT INTO equipment.machine(id,enterprise_id,line_id,code,name,sort_order,status) VALUES (?,?,?,?,?,?, 'ACTIVE')",
          machine, enterprise, line, code("MACHINE"), "Machine " + i, i);
      UUID signal = UUID.randomUUID();
      jdbc.update("INSERT INTO equipment.telemetry_signal(id,machine_id,code,name,value_type,signal_kind,semantic_code,status) "
              + "VALUES (?,?,?,?, 'DOUBLE','GAUGE','ROTATIONAL_SPEED','ACTIVE')",
          signal, machine, code("SIGNAL"), "Speed " + i);
      machines.add(machine);
      signals.add(signal);
    }
    return new Fixture(enterprise, machines, signals);
  }

  private String code(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT); }
  private record Fixture(UUID enterprise, List<UUID> machines, List<UUID> signals) {}
}
