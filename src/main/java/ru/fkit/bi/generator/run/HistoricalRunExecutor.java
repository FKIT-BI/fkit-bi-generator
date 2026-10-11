package ru.fkit.bi.generator.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ru.fkit.bi.generator.run.RunModels.Status;
import ru.fkit.bi.generator.telemetry.*;

/** Streams deterministic five-minute history chunks through bounded batches. */
@Component
public class HistoricalRunExecutor {
  private static final int BATCH_SIZE=1000;
  private static final long CHUNK_MS=Duration.ofMinutes(5).toMillis();
  private final RealtimeRunLifecycleRepository repository;
  private final RealtimeTelemetryExecutionService telemetry;
  private final ObjectMapper mapper;
  private final RealtimeBehaviorEvaluator evaluator=new RealtimeBehaviorEvaluator();

  public HistoricalRunExecutor(RealtimeRunLifecycleRepository repository,RealtimeTelemetryExecutionService telemetry,ObjectMapper mapper){this.repository=repository;this.telemetry=telemetry;this.mapper=mapper;}

  public void execute(RealtimeRunLifecycleRepository.ClaimedRun claim){
    try{
      JsonNode snapshot=claim.snapshot();Instant runFrom=claim.eventFrom(),runTo=claim.eventTo();
      if(runFrom==null||runTo==null||!runTo.isAfter(runFrom))throw new IllegalArgumentException("Historical range is invalid");
      JsonNode checkpoint=repository.checkpoint(claim.id());
      if(checkpoint.has("pendingBatch")){
        publishPending(claim,checkpoint);
        checkpoint=repository.checkpoint(claim.id());
      }
      Instant cursor=checkpoint.hasNonNull("completedThrough")?Instant.ofEpochMilli(checkpoint.path("completedThrough").asLong()):runFrom;
      List<UUID> machines=machineIds(snapshot);
      while(cursor.isBefore(runTo)){
        Status status=repository.state(claim.id()).status();
        if(status==Status.STOP_REQUESTED){repository.terminal(claim.id(),claim.workerId(),Status.STOPPED);return;}
        if(status!=Status.RUNNING)return;
        Instant chunkEnd=cursor.plusMillis(CHUNK_MS);if(chunkEnd.isAfter(runTo))chunkEnd=runTo;
        Instant effectiveEnd=onceEnd(snapshot,runFrom,chunkEnd);
        ChunkResult result=writeChunk(claim,snapshot,cursor,effectiveEnd,runFrom,checkpoint);
        if(result.stopped()){repository.terminal(claim.id(),claim.workerId(),Status.STOPPED);return;}
        if(repository.state(claim.id()).status()==Status.STOP_REQUESTED){repository.terminal(claim.id(),claim.workerId(),Status.STOPPED);return;}
        String[] position=position(snapshot,runFrom,effectiveEnd);
        ObjectNode completed=mapper.createObjectNode();completed.put("completedThrough",effectiveEnd.toEpochMilli());completed.put("chunkFrom",cursor.toEpochMilli());
        completed.put("cycleNumber",Long.parseLong(position[1]));if(position[0]==null)completed.putNull("phaseCode");else completed.put("phaseCode",position[0]);
        repository.completeHistoricalChunk(claim.id(),claim.workerId(),completed,effectiveEnd,position[0],Long.parseLong(position[1]),
            0,0,machines);
        checkpoint=completed;cursor=effectiveEnd;
        if(!effectiveEnd.isBefore(runTo)){repository.terminal(claim.id(),claim.workerId(),Status.COMPLETED);return;}
        if(isOnceComplete(snapshot,runFrom,cursor)){repository.terminal(claim.id(),claim.workerId(),Status.COMPLETED);return;}
      }
      repository.terminal(claim.id(),claim.workerId(),Status.COMPLETED);
    }catch(LeaseLostException lost){return;}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    catch(TelemetryConflictException e){repository.failed(claim.id(),claim.workerId(),"TELEMETRY_CONFLICT",safe(e),false,1);}
    catch(TelemetryValidationException|IllegalArgumentException e){repository.failed(claim.id(),claim.workerId(),"HISTORICAL_GENERATION_FAILED",safe(e),false,1);}
    catch(DataAccessException|TelemetryRetryableException e){repository.failed(claim.id(),claim.workerId(),"HISTORICAL_WRITE_FAILED",safe(e),true,1);}
    catch(RuntimeException e){repository.failed(claim.id(),claim.workerId(),"HISTORICAL_GENERATION_FAILED",safe(e),false,1);}
  }

  private ChunkResult writeChunk(RealtimeRunLifecycleRepository.ClaimedRun claim,JsonNode snapshot,Instant from,Instant to,Instant runFrom,JsonNode priorCheckpoint)throws InterruptedException{
    if(!to.isAfter(from))return new ChunkResult(0,0,false);
    PriorityQueue<SignalCursor> queue=new PriorityQueue<>(Comparator.comparing((SignalCursor c)->c.due).thenComparing(c->c.machineId.toString()).thenComparing(c->c.signalId.toString()));
    for(JsonNode machine:snapshot.path("machines")){
      UUID machineId=UUID.fromString(machine.path("machineId").asText());Set<UUID> signals=new TreeSet<>(Comparator.comparing(UUID::toString));
      for(JsonNode phase:machine.path("phases"))for(JsonNode signal:phase.path("signals"))signals.add(UUID.fromString(signal.path("signalId").asText()));
      long sequenceDuration=sequenceDuration(snapshot);
      for(UUID signal:signals){SignalCursor c=new SignalCursor(machine, machineId,signal,snapshot.path("cycleMode").asText(),runFrom,sequenceDuration);
        c.seek(from);if(c.due!=null&&c.due.isBefore(to))queue.add(c);}
    }
    List<TelemetryMeasurement> buffer=new ArrayList<>(BATCH_SIZE);long generated=0,written=0;int ordinal=0;JsonNode liveCheckpoint=priorCheckpoint;
    long chunkIndex=Math.max(0,Duration.between(runFrom,from).toMillis()/CHUNK_MS);
    while(!queue.isEmpty()){
      SignalCursor c=queue.poll();if(c.due==null||!c.due.isBefore(to))break;
      long tick=(c.due.toEpochMilli()-c.phaseStart.toEpochMilli())/c.interval;
      var value=evaluator.evaluate(c.behavior,c.definition.path("value_type").asText(),claim.randomSeed(),c.machineId,c.signalId,c.phaseCode,c.cycle,tick,tick*c.interval);
      if(!value.missing()){
        buffer.add(new TelemetryMeasurement(c.signalId,c.due,value.value(),value.quality()));generated++;
        if(buffer.size()==BATCH_SIZE){BatchReceipt receipt=flush(claim,buffer,from,ordinal++,chunkIndex,liveCheckpoint);written+=receipt.count();liveCheckpoint=receipt.checkpoint();buffer.clear();
          if(repository.state(claim.id()).status()==Status.STOP_REQUESTED)return new ChunkResult(generated,written,true);}
      }
      c.seek(c.due.plusMillis(1));if(c.due!=null&&c.due.isBefore(to))queue.add(c);
    }
    if(!buffer.isEmpty()){BatchReceipt receipt=flush(claim,buffer,from,ordinal,chunkIndex,liveCheckpoint);written+=receipt.count();buffer.clear();
      if(repository.state(claim.id()).status()==Status.STOP_REQUESTED)return new ChunkResult(generated,written,true);}
    return new ChunkResult(generated,written,false);
  }

  private BatchReceipt flush(RealtimeRunLifecycleRepository.ClaimedRun claim,List<TelemetryMeasurement> rows,Instant chunkFrom,int ordinal,long chunkIndex,JsonNode checkpoint)throws InterruptedException{
    long sequence=Math.addExact(Math.multiplyExact(chunkIndex,1_000_000L),ordinal+1L);
    String batchId=claim.id()+":hist:"+chunkFrom.toEpochMilli()+":"+ordinal;
    ObjectNode pending=checkpoint.deepCopy();pending.put("chunkFrom",chunkFrom.toEpochMilli());ObjectNode envelope=pending.putObject("pendingBatch");envelope.put("sequence",sequence);envelope.put("batchId",batchId);ArrayNode items=envelope.putArray("measurements");
    for(TelemetryMeasurement row:rows)items.add(encode(row));
    envelope.put("count",rows.size());envelope.put("maxObservedAt",rows.getLast().observedAt().toEpochMilli());
    repository.historicalPending(claim.id(),claim.workerId(),sequence,pending);
    long before=System.nanoTime();TelemetryWriteResult receipt=telemetry.publish("fkit-generator",batchId,sequence,claim.id(),TelemetryBatch.Mode.HISTORICAL,rows);
    if(receipt.droppedMeasurements()>0)throw new TelemetryRetryableException("HISTORICAL_BATCH_DROPPED");
    pending.remove("pendingBatch");pending.put("lastAcknowledgedSequence",Math.max(checkpoint.path("lastAcknowledgedSequence").asLong(0),sequence));
    repository.historicalAcknowledge(claim.id(),claim.workerId(),sequence,pending,rows.size(),rows.getLast().observedAt());
    long minMillis=rows.size()*1000L/1500L;long elapsed=(System.nanoTime()-before)/1_000_000L;long sleep=Math.max(0,minMillis-elapsed);if(sleep>0)Thread.sleep(sleep);
    return new BatchReceipt(rows.size(),pending);
  }

  private void publishPending(RealtimeRunLifecycleRepository.ClaimedRun claim,JsonNode checkpoint){
    JsonNode p=checkpoint.path("pendingBatch");List<TelemetryMeasurement> rows=new ArrayList<>();for(JsonNode row:p.path("measurements"))rows.add(decode(row));
    TelemetryWriteResult receipt=telemetry.publish("fkit-generator",p.path("batchId").asText(),p.path("sequence").asLong(),claim.id(),TelemetryBatch.Mode.HISTORICAL,rows);
    if(receipt.droppedMeasurements()>0)throw new TelemetryRetryableException("HISTORICAL_BATCH_DROPPED");
    ObjectNode clean=checkpoint.deepCopy();clean.remove("pendingBatch");long sequence=p.path("sequence").asLong();clean.put("lastAcknowledgedSequence",Math.max(checkpoint.path("lastAcknowledgedSequence").asLong(0),sequence));
    repository.historicalAcknowledge(claim.id(),claim.workerId(),sequence,clean,p.path("count").asLong(rows.size()),Instant.ofEpochMilli(p.path("maxObservedAt").asLong(rows.getLast().observedAt().toEpochMilli())));
  }

  private Instant onceEnd(JsonNode snapshot,Instant runFrom,Instant proposed){if(!"ONCE".equals(snapshot.path("cycleMode").asText()))return proposed;long duration=sequenceDuration(snapshot);Instant end=runFrom.plusMillis(duration);return end.isBefore(proposed)?end:proposed;}
  private boolean isOnceComplete(JsonNode snapshot,Instant runFrom,Instant cursor){return "ONCE".equals(snapshot.path("cycleMode").asText())&&!cursor.isBefore(runFrom.plusMillis(sequenceDuration(snapshot)));}
  private long sequenceDuration(JsonNode snapshot){long total=0;for(JsonNode phase:snapshot.path("scenario").path("phases"))total=Math.addExact(total,phase.path("durationMs").asLong());return total;}
  private String[] position(JsonNode snapshot,Instant from,Instant at){long duration=sequenceDuration(snapshot);if(duration<=0)return new String[]{null,"0"};long elapsed=Math.max(0,Duration.between(from,at).toMillis());long cycle="LOOP".equals(snapshot.path("cycleMode").asText())?elapsed/duration:0;long within=elapsed%duration,offset=0;for(JsonNode phase:snapshot.path("scenario").path("phases")){long end=offset+phase.path("durationMs").asLong();if(within<end)return new String[]{phase.path("code").asText(),Long.toString(cycle)};offset=end;}return new String[]{null,Long.toString(cycle)};}
  private List<UUID> machineIds(JsonNode snapshot){List<UUID> ids=new ArrayList<>();for(JsonNode m:snapshot.path("machines"))ids.add(UUID.fromString(m.path("machineId").asText()));return ids;}
  private JsonNode encode(TelemetryMeasurement m){ObjectNode r=mapper.createObjectNode();r.put("signalId",m.signalId().toString());r.put("observedAt",m.observedAt().toEpochMilli());r.put("quality",m.sourceQuality());Object v=m.value();if(v instanceof Double d){r.put("type","DOUBLE");r.put("value",d);}else if(v instanceof Long l){r.put("type","LONG");r.put("value",l);}else if(v instanceof Boolean b){r.put("type","BOOLEAN");r.put("value",b);}else{r.put("type","STRING");r.put("value",v.toString());}return r;}
  private TelemetryMeasurement decode(JsonNode r){Object v=switch(r.path("type").asText()){case "DOUBLE"->r.path("value").asDouble();case "LONG"->r.path("value").asLong();case "BOOLEAN"->r.path("value").asBoolean();case "STRING"->r.path("value").asText();default->throw new IllegalArgumentException("Pending historical batch has invalid type");};return new TelemetryMeasurement(UUID.fromString(r.path("signalId").asText()),Instant.ofEpochMilli(r.path("observedAt").asLong()),v,(short)r.path("quality").asInt());}
  private String safe(Throwable e){String s=e.getMessage()==null?"Historical execution failed":e.getMessage();return s.substring(0,Math.min(500,s.length()));}
  private record ChunkResult(long generated,long written,boolean stopped){}
  private record BatchReceipt(long count,JsonNode checkpoint){}

  private final class SignalCursor{
    final JsonNode machine;final UUID machineId,signalId;final String cycleMode;final Instant runFrom;final long sequenceDuration;
    Instant due,phaseStart;JsonNode behavior,definition;String phaseCode;long cycle,interval,phaseEndMillis;
    SignalCursor(JsonNode machine,UUID machineId,UUID signalId,String cycleMode,Instant runFrom,long sequenceDuration){this.machine=machine;this.machineId=machineId;this.signalId=signalId;this.cycleMode=cycleMode;this.runFrom=runFrom;this.sequenceDuration=sequenceDuration;}
    void seek(Instant lower){
      due=null;behavior=null;definition=null;
      if(sequenceDuration<=0)return;
      JsonNode phases=machine.path("phases");int n=phases.size();if(n==0)return;
      long elapsed=Math.max(0,Duration.between(runFrom,lower).toMillis());boolean loop="LOOP".equals(cycleMode);
      if(!loop&&elapsed>=sequenceDuration)return;
      long firstCycle=loop?elapsed/sequenceDuration:0;long within=loop?elapsed%sequenceDuration:elapsed;
      int firstPhase=0;long offset=0;
      for(int i=0;i<n;i++){long end=offset+phases.get(i).path("durationMs").asLong();if(within<end){firstPhase=i;break;}offset=end;}
      for(long c=firstCycle;;c++){
        if(!loop&&c>0)return;
        long phaseOffset=0;
        for(int i=0;i<n;i++){
          JsonNode phase=phases.get(i);long duration=phase.path("durationMs").asLong();
          if(c==firstCycle&&i<firstPhase){phaseOffset+=duration;continue;}
          Instant candidateStart=runFrom.plusMillis(c*sequenceDuration+phaseOffset);Instant candidateEnd=candidateStart.plusMillis(duration);
          if(!candidateEnd.isAfter(lower)){phaseOffset+=duration;continue;}
          JsonNode machinePhase=null;for(JsonNode p:machine.path("phases"))if(p.path("code").asText().equals(phase.path("code").asText())){machinePhase=p;break;}
          JsonNode signal=null;if(machinePhase!=null)for(JsonNode s:machinePhase.path("signals"))if(signalId.toString().equals(s.path("signalId").asText())){signal=s;break;}
          if(signal!=null){JsonNode candidateBehavior=signal.path("behavior");long candidateInterval=candidateBehavior.path("generationIntervalMs").asLong();
            if(candidateInterval<1000)throw new IllegalArgumentException("Historical signal interval is invalid");
            long delta=Math.max(0,Duration.between(candidateStart,lower).toMillis());long tick=Math.max(0,(delta+candidateInterval-1)/candidateInterval);
            Instant candidateDue=candidateStart.plusMillis(tick*candidateInterval);
            if(candidateDue.isBefore(candidateEnd)){due=candidateDue;phaseStart=candidateStart;phaseEndMillis=candidateEnd.toEpochMilli();phaseCode=phase.path("code").asText();cycle=c;interval=candidateInterval;behavior=candidateBehavior;definition=signal.path("definition");return;}
          }
          phaseOffset+=duration;
        }
        if(!loop)return;
        firstPhase=0;
      }
    }
  }
}
