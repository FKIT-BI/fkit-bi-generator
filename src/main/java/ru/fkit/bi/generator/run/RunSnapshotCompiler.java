package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.fkit.bi.generator.run.RunModels.CreateRequest;
import ru.fkit.bi.generator.scenario.ScenarioModels.BaseBehavior;
import ru.fkit.bi.generator.scenario.ScenarioModels.BehaviorOverride;
import ru.fkit.bi.generator.scenario.ScenarioModels.Detail;
import ru.fkit.bi.generator.scenario.ScenarioModels.MachineTarget;
import ru.fkit.bi.generator.scenario.ScenarioModels.OverrideAction;
import ru.fkit.bi.generator.scenario.ScenarioModels.Phase;

/** Captures the current equipment and expands Scenario inheritance before a Run is persisted. */
@Component
public class RunSnapshotCompiler {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public RunSnapshotCompiler(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public ObjectNode compile(Detail scenario, CreateRequest request, long seed) {
    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.put("scenarioId", scenario.id().toString());
    snapshot.put("enterpriseId", scenario.enterpriseId().toString());
    snapshot.put("scenarioCode", scenario.code());
    snapshot.put("scenarioName", scenario.name());
    snapshot.put("cycleMode", scenario.cycleMode().name());
    snapshot.put("randomSeed", seed);
    ObjectNode execution = snapshot.putObject("execution");
    execution.put("mode", request.mode().name());
    putInstant(execution, "from", request.from());
    putInstant(execution, "to", request.to());
    putInstant(execution, "scheduledStartAt", request.scheduledStartAt());
    putInstant(execution, "eventTo", request.eventTo());
    if (request.durationMs() == null) execution.putNull("durationMs");
    else execution.put("durationMs", request.durationMs());
    ObjectNode enterprise = snapshot.putObject("enterprise");
    copyRow(enterprise, jdbc.queryForMap(
        "SELECT id,code,name,timezone,status FROM equipment.enterprise WHERE id=?", scenario.enterpriseId()));

    ArrayNode machineSnapshots = snapshot.putArray("machines");
    for (MachineTarget target : scenario.machineTargets()) {
      ObjectNode machine = machineSnapshots.addObject();
      Map<String, Object> machineRow = jdbc.queryForMap(
          "SELECT m.id,m.enterprise_id,m.line_id,m.code,m.name,m.machine_type,m.inventory_no,m.manufacturer,m.model,"
              + "m.commissioning_year,m.nominal_rate,m.production_unit_code,m.status AS machine_status,"
              + "l.code AS line_code,l.name AS line_name,l.status AS line_status "
              + "FROM equipment.machine m JOIN equipment.production_line l ON l.id=m.line_id WHERE m.id=?",
          target.machineId());
      ObjectNode machineData = machine.putObject("machine");
      copyRow(machineData, machineRow);
      machine.put("machineId", target.machineId().toString());
      ArrayNode phases = machine.putArray("phases");
      Map<UUID, JsonNode> base = new LinkedHashMap<>();
      if (target.baseBehaviors() != null) {
        for (BaseBehavior behavior : target.baseBehaviors()) {
          if (behavior != null && behavior.signalId() != null && behavior.behavior() != null) {
            base.put(behavior.signalId(), behavior.behavior().deepCopy());
          }
        }
      }
      Set<String> machineSignalIds = new HashSet<>();
      jdbc.query("SELECT id FROM equipment.telemetry_signal WHERE machine_id=?",
          rs -> { machineSignalIds.add(rs.getString("id")); }, target.machineId());
      for (Phase phase : scenario.phases()) {
        Map<UUID, JsonNode> effective = new LinkedHashMap<>();
        base.forEach((signalId, behavior) -> effective.put(signalId, behavior.deepCopy()));
        if (phase.behaviorOverrides() != null) {
          for (BehaviorOverride override : phase.behaviorOverrides()) {
            if (override == null || override.signalId() == null || !machineSignalIds.contains(override.signalId().toString())) continue;
            if (override.action() == OverrideAction.DISABLE) effective.remove(override.signalId());
            else if (override.action() == OverrideAction.REPLACE && override.behavior() != null)
              effective.put(override.signalId(), override.behavior().deepCopy());
          }
        }
        ObjectNode phaseNode = phases.addObject();
        phaseNode.put("code", phase.code());
        phaseNode.put("name", phase.name());
        if (phase.durationMs() == null) phaseNode.putNull("durationMs"); else phaseNode.put("durationMs", phase.durationMs());
        if (phase.expectedState() == null) phaseNode.putNull("expectedState"); else phaseNode.put("expectedState", phase.expectedState().name());
        ArrayNode signals = phaseNode.putArray("signals");
        for (Map.Entry<UUID, JsonNode> entry : effective.entrySet()) {
          ObjectNode signalNode = signals.addObject();
          signalNode.put("signalId", entry.getKey().toString());
          signalNode.set("behavior", entry.getValue().deepCopy());
          List<Map<String, Object>> signalRows = jdbc.query(
              "SELECT s.id,s.machine_id,s.component_id,s.code,s.name,s.value_type,s.signal_kind,s.semantic_code,"
                  + "s.custom_semantic_code,s.unit_code,s.expected_interval_ms,s.stale_after_ms,s.valid_min,s.valid_max,"
                  + "s.status AS signal_status,c.code AS component_code,c.name AS component_name,c.kind AS component_kind "
                  + "FROM equipment.telemetry_signal s LEFT JOIN equipment.component c "
                  + "ON c.id=s.component_id AND c.machine_id=s.machine_id WHERE s.id=? AND s.machine_id=?",
              (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : List.of("id", "machine_id", "component_id", "code", "name", "value_type",
                    "signal_kind", "semantic_code", "custom_semantic_code", "unit_code", "expected_interval_ms",
                    "stale_after_ms", "valid_min", "valid_max", "signal_status", "component_code",
                    "component_name", "component_kind")) row.put(column, rs.getObject(column));
                return row;
              }, entry.getKey(), target.machineId());
          if (signalRows.isEmpty()) continue;
          ObjectNode signalData = signalNode.putObject("definition");
          copyRow(signalData, signalRows.getFirst());
        }
      }
    }
    ObjectNode scenarioNode = snapshot.putObject("scenario");
    scenarioNode.put("id", scenario.id().toString());
    scenarioNode.put("code", scenario.code());
    scenarioNode.put("cycleMode", scenario.cycleMode().name());
    ArrayNode originalPhases = scenarioNode.putArray("phases");
    for (Phase phase : scenario.phases()) originalPhases.add(mapper.valueToTree(phase));
    return snapshot;
  }

  public long sequenceDurationMs(Detail scenario) {
    long total = 0;
    for (Phase phase : scenario.phases()) {
      if (phase.durationMs() == null) return 0;
      total = Math.addExact(total, phase.durationMs());
    }
    return total;
  }

  private void copyRow(ObjectNode target, Map<String, Object> row) {
    row.forEach((name, value) -> {
      if (value == null) { target.putNull(name); return; }
      if (value instanceof UUID uuid) { target.put(name, uuid.toString()); return; }
      if (value instanceof Timestamp timestamp) { target.put(name, timestamp.toInstant().toString()); return; }
      if (value instanceof Number number) { target.put(name, number.doubleValue()); return; }
      if (value instanceof Boolean bool) { target.put(name, bool); return; }
      target.set(name, mapper.valueToTree(value));
    });
  }

  private void putInstant(ObjectNode target, String name, java.time.Instant value) {
    if (value == null) target.putNull(name); else target.put(name, value.toString());
  }
}
