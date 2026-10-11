package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.fkit.bi.generator.run.RunModels.Conflict;

/** Promotes due one-shot scheduled runs only after current equipment readiness is rechecked. */
@Component
public class ScheduledRunStarter {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RunRepository runs;

  public ScheduledRunStarter(JdbcTemplate jdbc,PlatformTransactionManager manager,RunRepository runs){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.runs=runs;}

  @Scheduled(fixedDelayString="${generator.scheduled.poll-delay-ms:500}",initialDelayString="${generator.scheduled.initial-delay-ms:0}")
  public void poll(){
    for(int i=0;i<16;i++){
      Boolean processed=tx.execute(status->startOne());
      if(!Boolean.TRUE.equals(processed))return;
    }
  }

  private boolean startOne(){
    List<ScheduledRunRow> rows=jdbc.query("SELECT id,enterprise_id,scheduled_start_at,event_to,snapshot FROM generator.generation_run "
        + "WHERE mode='SCHEDULED' AND status='SCHEDULED' AND scheduled_start_at<=clock_timestamp() "
        + "ORDER BY scheduled_start_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
        (rs,n)->new ScheduledRunRow(rs.getObject("id",UUID.class),rs.getObject("enterprise_id",UUID.class),
            rs.getTimestamp("scheduled_start_at").toInstant(),instant(rs.getTimestamp("event_to")),read(rs.getString("snapshot"))));
    if(rows.isEmpty())return false;
    ScheduledRunRow run=rows.getFirst();Instant now=databaseNow();
    if(Duration.between(run.scheduledAt(),now).compareTo(Duration.ofSeconds(30))>0){fail(run.id(),"SCHEDULE_MISSED","Scheduled start was missed by more than 30 seconds");return true;}
    String readiness=readinessFailure(run);
    if(readiness!=null){fail(run.id(),"SCHEDULED_READINESS_FAILED",readiness);return true;}
    long duration=run.snapshot().path("execution").path("durationMs").asLong(0);
    if(duration<=0&&run.snapshot().path("execution").path("eventTo").isNull()
        &&"ONCE".equals(run.snapshot().path("cycleMode").asText()))duration=sequenceDuration(run.snapshot());
    Instant eventTo=run.eventTo();
    if(run.snapshot().path("execution").path("eventTo").isNull()&&duration>0)eventTo=now.plusMillis(duration);
    Instant reservedFrom=now;
    List<UUID> machineIds=machineIds(run.snapshot());
    List<Conflict> conflicts=runs.reservationConflicts(machineIds,reservedFrom,eventTo).stream()
        .filter(c->!run.id().equals(c.conflictingRunId())).toList();
    if(!conflicts.isEmpty()){fail(run.id(),"RUN_RESERVATION_CONFLICT","A machine reservation conflicts with the actual scheduled start");return true;}
    jdbc.update("UPDATE generator.generation_run SET status='QUEUED',event_from=?,event_to=?,updated_at=? WHERE id=? AND status='SCHEDULED'",
        Timestamp.from(now),timestamp(eventTo),Timestamp.from(now),run.id());
    jdbc.update("UPDATE generator.generation_run_reservation SET reserved_from=?,reserved_to=? WHERE run_id=? AND released_at IS NULL",
        Timestamp.from(reservedFrom),timestamp(eventTo),run.id());
    return true;
  }

  private String readinessFailure(ScheduledRunRow run){
    String enterpriseStatus=jdbc.query("SELECT status FROM equipment.enterprise WHERE id=?",(rs,n)->rs.getString(1),run.enterpriseId()).stream().findFirst().orElse(null);
    if(!"ACTIVE".equals(enterpriseStatus))return "Enterprise is missing or no longer active";
    for(JsonNode machine:run.snapshot().path("machines")){
      UUID id=UUID.fromString(machine.path("machineId").asText());JsonNode captured=machine.path("machine");
      List<CurrentMachine> rows=jdbc.query("SELECT enterprise_id,line_id,status FROM equipment.machine WHERE id=?",
          (rs,n)->new CurrentMachine(rs.getObject("enterprise_id",UUID.class),rs.getObject("line_id",UUID.class),rs.getString("status")),id);
      if(rows.isEmpty())return "A target machine no longer exists";
      CurrentMachine current=rows.getFirst();
      if(!run.enterpriseId().equals(current.enterpriseId())||!"ACTIVE".equals(current.status()))return "A target machine is no longer owned by the enterprise or active";
      UUID capturedLine=uuid(captured.get("line_id"));
      if(capturedLine==null||!capturedLine.equals(current.lineId()))return "A target machine line assignment changed";
      String lineStatus=jdbc.query("SELECT status FROM equipment.production_line WHERE id=? AND enterprise_id=?",
          (rs,n)->rs.getString(1),current.lineId(),run.enterpriseId()).stream().findFirst().orElse(null);
      if(!"ACTIVE".equals(lineStatus))return "A target production line is missing or inactive";
      Set<UUID> signalIds=new LinkedHashSet<>();for(JsonNode phase:machine.path("phases"))for(JsonNode signal:phase.path("signals"))signalIds.add(UUID.fromString(signal.path("signalId").asText()));
      for(UUID signalId:signalIds){String signalStatus=jdbc.query("SELECT status FROM equipment.telemetry_signal WHERE id=? AND machine_id=?",
          (rs,n)->rs.getString(1),signalId,id).stream().findFirst().orElse(null);if(!"ACTIVE".equals(signalStatus))return "A required signal is missing, reassigned or inactive";}
    }
    return null;
  }

  private void fail(UUID id,String code,String message){
    Instant now=databaseNow();
    jdbc.update("UPDATE generator.generation_run SET status='FAILED',failure_code=?,failure_message=?,failure_at=?,failure_retryable=FALSE,finished_at=?,updated_at=? WHERE id=?",
        code,message,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),id);
    jdbc.update("UPDATE generator.generation_run_attempt SET finished_at=?,failure_code=?,failure_message=?,failure_at=?,failure_retryable=FALSE WHERE run_id=? AND attempt_no=(SELECT attempt_no FROM generator.generation_run WHERE id=?)",
        Timestamp.from(now),code,message,Timestamp.from(now),id,id);
    jdbc.update("UPDATE generator.generation_run_reservation SET released_at=? WHERE run_id=? AND released_at IS NULL",Timestamp.from(now),id);
  }
  private List<UUID> machineIds(JsonNode snapshot){java.util.ArrayList<UUID> ids=new java.util.ArrayList<>();for(JsonNode m:snapshot.path("machines"))ids.add(UUID.fromString(m.path("machineId").asText()));return ids;}
  private long sequenceDuration(JsonNode snapshot){long total=0;for(JsonNode phase:snapshot.path("scenario").path("phases"))total=Math.addExact(total,phase.path("durationMs").asLong(0));return total;}
  private Instant databaseNow(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
  private static UUID uuid(JsonNode node){return node==null||node.isNull()?null:UUID.fromString(node.asText());}
  private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
  private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
  private JsonNode read(String text){try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
  private record ScheduledRunRow(UUID id,UUID enterpriseId,Instant scheduledAt,Instant eventTo,JsonNode snapshot){}
  private record CurrentMachine(UUID enterpriseId,UUID lineId,String status){}
}
