package ru.fkit.bi.generator.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.fkit.bi.generator.scenario.ScenarioModels.*;

@Repository
public class ScenarioRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ScenarioRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional
  public Detail insert(CreateRequest request, ValidationResult validation) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    String definition = json(new Definition(request.machineTargets(), request.phases()));
    jdbc.update("INSERT INTO generator.generation_scenario(id,enterprise_id,code,name,description,status,cycle_mode,definition,created_at,updated_at) VALUES (?,?,?,?,?,'DISABLED',?::text,?::jsonb,?,?)",
        id, request.enterpriseId(), request.code(), request.name().trim(), request.description(), request.cycleMode().name(), definition,
        Timestamp.from(now), Timestamp.from(now));
    replaceMachineLinks(id, request.enterpriseId(), request.machineTargets());
    return new Detail(id, request.enterpriseId(), request.code(), request.name().trim(), request.description(), Status.DISABLED,
        request.cycleMode(), request.machineTargets(), request.phases(), validation, now, now);
  }

  @Transactional
  public Optional<Detail> replace(UUID id, UpdateRequest request, ValidationResult validation) {
    List<Row> rows = jdbc.query("SELECT id,enterprise_id,code,name,description,status,cycle_mode,definition,created_at,updated_at FROM generator.generation_scenario WHERE id=? FOR UPDATE", this::row, id);
    if (rows.isEmpty()) return Optional.empty();
    Row old = rows.getFirst();
    String definition = json(new Definition(request.machineTargets(), request.phases()));
    jdbc.update("UPDATE generator.generation_scenario SET name=?,description=?,cycle_mode=?,definition=?::jsonb,updated_at=clock_timestamp() WHERE id=?",
        request.name().trim(), request.description(), request.cycleMode().name(), definition, id);
    replaceMachineLinks(id, old.enterpriseId(), request.machineTargets());
    return get(id);
  }

  @Transactional
  public Optional<Detail> setStatus(UUID id, Status status, ValidationResult validation) {
    List<Row> rows = jdbc.query("SELECT id,enterprise_id,code,name,description,status,cycle_mode,definition,created_at,updated_at FROM generator.generation_scenario WHERE id=? FOR UPDATE", this::row, id);
    if (rows.isEmpty()) return Optional.empty();
    jdbc.update("UPDATE generator.generation_scenario SET status=?,updated_at=clock_timestamp() WHERE id=?", status.name(), id);
    return get(id);
  }

  public Optional<Detail> get(UUID id) {
    List<Row> rows = jdbc.query("SELECT id,enterprise_id,code,name,description,status,cycle_mode,definition,created_at,updated_at FROM generator.generation_scenario WHERE id=?", this::row, id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(toDetail(rows.getFirst(), null));
  }

  /** Holds the Scenario row stable while a Run snapshot is compiled in the caller transaction. */
  public boolean lockForRun(UUID id) {
    return !jdbc.query("SELECT id FROM generator.generation_scenario WHERE id=? FOR SHARE",
        (rs, n) -> rs.getObject(1, UUID.class), id).isEmpty();
  }

  public Page list(UUID enterpriseId, Status status, String search, int page, int size) {
    String pattern = search == null ? null : "%" + search.toLowerCase() + "%";
    Long total = jdbc.queryForObject("SELECT count(*) FROM generator.generation_scenario WHERE enterprise_id=? AND status=? AND (? IS NULL OR lower(code) LIKE ? OR lower(name) LIKE ?)",
        Long.class, enterpriseId, status.name(), pattern, pattern, pattern);
    List<Summary> items = jdbc.query("SELECT id,enterprise_id,code,name,status,cycle_mode,definition,created_at,updated_at FROM generator.generation_scenario WHERE enterprise_id=? AND status=? AND (? IS NULL OR lower(code) LIKE ? OR lower(name) LIKE ?) ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
        (rs, n) -> { Definition d = parse(rs.getString("definition")); return new Summary(rs.getObject("id", UUID.class), rs.getObject("enterprise_id", UUID.class), rs.getString("code"), rs.getString("name"), Status.valueOf(rs.getString("status")), CycleMode.valueOf(rs.getString("cycle_mode")), d.machineTargets().size(), d.phases().size(), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()); },
        enterpriseId, status.name(), pattern, pattern, pattern, size, (long) page * size);
    long count = total == null ? 0 : total;
    return new Page(items, page, size, count, (int) ((count + size - 1) / size));
  }

  private void replaceMachineLinks(UUID scenarioId, UUID enterpriseId, List<MachineTarget> targets) {
    jdbc.update("DELETE FROM generator.generation_scenario_machine WHERE scenario_id=?", scenarioId);
    for (MachineTarget target : targets) jdbc.update("INSERT INTO generator.generation_scenario_machine(enterprise_id,scenario_id,machine_id) VALUES (?,?,?)", enterpriseId, scenarioId, target.machineId());
  }

  private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(rs.getObject("id", UUID.class), rs.getObject("enterprise_id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("description"), Status.valueOf(rs.getString("status")), CycleMode.valueOf(rs.getString("cycle_mode")), parse(rs.getString("definition")), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }
  private Detail toDetail(Row r, ValidationResult validation) { return new Detail(r.id(), r.enterpriseId(), r.code(), r.name(), r.description(), r.status(), r.cycleMode(), r.definition().machineTargets(), r.definition().phases(), validation, r.createdAt(), r.updatedAt()); }
  private String json(Definition value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
  private Definition parse(String value) { try { return mapper.readValue(value, Definition.class); } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored scenario definition", e); } }
  private record Definition(List<MachineTarget> machineTargets, List<Phase> phases) {}
  private record Row(UUID id, UUID enterpriseId, String code, String name, String description, Status status, CycleMode cycleMode, Definition definition, Instant createdAt, Instant updatedAt) {}
}
