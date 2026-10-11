package ru.fkit.bi.generator.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.fkit.bi.generator.scenario.ScenarioModels.*;

@Component
public class ScenarioValidator {
  private final JdbcTemplate jdbc;
  public ScenarioValidator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public ValidationResult validate(UUID enterpriseId, String code, String name, String description, CycleMode cycleMode,
      List<MachineTarget> targets, List<Phase> phases) {
    List<ValidationIssue> issues = new ArrayList<>();
    if (enterpriseId == null) issue(issues,"ENTERPRISE_REQUIRED","enterpriseId","Enterprise is required");
    if (code == null || !code.matches("^[A-Z][A-Z0-9_]{1,63}$")) issue(issues,"INVALID_CODE","code","Code must be 2–64 uppercase letters, digits or underscores and start with a letter");
    if (name == null || name.isBlank() || name.length()>200) issue(issues,"INVALID_NAME","name","Name must contain 1–200 characters");
    if (name != null && !name.equals(name.trim())) issue(issues,"INVALID_NAME","name","Name cannot start or end with whitespace");
    if (description != null && description.length() > 2000) issue(issues,"DESCRIPTION_TOO_LONG","description","Description must be at most 2000 characters");
    if (cycleMode == null) issue(issues,"CYCLE_MODE_REQUIRED","cycleMode","Cycle mode is required");
    if (targets == null || targets.isEmpty() || targets.size()>20) issue(issues,"MACHINE_TARGETS_REQUIRED","machineTargets","Provide between 1 and 20 machine targets");
    if (phases == null || phases.isEmpty() || phases.size()>20) issue(issues,"PHASES_REQUIRED","phases","Provide between 1 and 20 phases");
    if (enterpriseId != null) {
      List<String> owners = jdbc.query("SELECT status FROM equipment.enterprise WHERE id=? FOR SHARE", (rs,n)->rs.getString(1), enterpriseId);
      if (owners.isEmpty()) issue(issues,"ENTERPRISE_NOT_FOUND","enterpriseId","Enterprise does not exist");
      else if (!"ACTIVE".equals(owners.getFirst())) issue(issues,"ENTERPRISE_NOT_ACTIVE","enterpriseId","Enterprise must be active");
    }
    Map<UUID, MachineInfo> machines = new HashMap<>();
    Map<UUID, SignalInfo> signals = new HashMap<>();
    Set<UUID> machineIds = new HashSet<>(); Set<UUID> behaviorSignalIds = new HashSet<>();
    if (targets != null) for (int i=0;i<targets.size();i++) {
      MachineTarget target=targets.get(i); String path="machineTargets["+i+"]";
      if (target==null || target.machineId()==null) { issue(issues,"MACHINE_REQUIRED",path+".machineId","Machine is required"); continue; }
      if (!machineIds.add(target.machineId())) issue(issues,"DUPLICATE_MACHINE",path+".machineId","Machine target must be unique");
      List<MachineInfo> rows=jdbc.query("SELECT enterprise_id,status,line_id FROM equipment.machine WHERE id=? FOR SHARE",(rs,n)->new MachineInfo(rs.getObject("enterprise_id",UUID.class),rs.getString("status"),rs.getObject("line_id",UUID.class),null),target.machineId());
      if(rows.isEmpty()) issue(issues,"MACHINE_NOT_FOUND",path+".machineId","Machine does not exist");
      else { MachineInfo m=rows.getFirst(); machines.put(target.machineId(),m);
        if(!Objects.equals(enterpriseId,m.enterpriseId())) issue(issues,"MACHINE_ENTERPRISE_MISMATCH",path+".machineId","Machine must belong to this enterprise");
        if(!"ACTIVE".equals(m.status())) issue(issues,"MACHINE_NOT_ACTIVE",path+".machineId","Machine must be active");
        if(m.lineId()==null) issue(issues,"MACHINE_NOT_ASSIGNED",path+".machineId","Machine must be assigned to a production line");
        else { List<String> lineStatuses=jdbc.query("SELECT status FROM equipment.production_line WHERE id=? FOR SHARE",(rs,n)->rs.getString(1),m.lineId()); if(lineStatuses.isEmpty()||!"ACTIVE".equals(lineStatuses.getFirst())) issue(issues,"LINE_NOT_ACTIVE",path+".machineId","Machine production line must be active"); }
      }
      List<BaseBehavior> base=target.baseBehaviors()==null?List.of():target.baseBehaviors(); Set<UUID> local=new HashSet<>();
      for(int j=0;j<base.size();j++){BaseBehavior b=base.get(j); String bp=path+".baseBehaviors["+j+"]"; if(b==null||b.signalId()==null){issue(issues,"SIGNAL_REQUIRED",bp+".signalId","Signal is required");continue;} if(!local.add(b.signalId()))issue(issues,"DUPLICATE_SIGNAL",bp+".signalId","Signal behavior must be unique per machine"); behaviorSignalIds.add(b.signalId()); validateBehavior(b.behavior(),bp+".behavior",null,issues);}
    }
    if(!machineIds.isEmpty()) {
      String marks=String.join(",",Collections.nCopies(machineIds.size(),"?"));
      Object[] args=machineIds.toArray();
      jdbc.query("SELECT id,machine_id,value_type,signal_kind,status FROM equipment.telemetry_signal WHERE machine_id IN ("+marks+") FOR SHARE",rs->{ UUID id=rs.getObject("id",UUID.class); signals.put(id,new SignalInfo(rs.getObject("machine_id",UUID.class),rs.getString("value_type"),rs.getString("signal_kind"),rs.getString("status"))); },args);
    }
    if(targets!=null)for(int i=0;i<targets.size();i++){MachineTarget t=targets.get(i);if(t==null||t.baseBehaviors()==null)continue;for(int j=0;j<t.baseBehaviors().size();j++){BaseBehavior b=t.baseBehaviors().get(j);if(b==null||b.signalId()==null)continue;SignalInfo s=signals.get(b.signalId());if(s!=null&&!Objects.equals(s.machineId(),t.machineId()))issue(issues,"SIGNAL_MACHINE_MISMATCH","machineTargets["+i+"].baseBehaviors["+j+"].signalId","Signal must belong to this machine target");}}
    if(phases!=null){Set<String> codes=new HashSet<>(); for(int i=0;i<phases.size();i++){Phase p=phases.get(i);String path="phases["+i+"]";if(p==null)continue;
      if(p.code()==null||!p.code().matches("^[A-Z][A-Z0-9_]{0,63}$"))issue(issues,"INVALID_PHASE_CODE",path+".code","Phase code is invalid");else if(!codes.add(p.code()))issue(issues,"DUPLICATE_PHASE_CODE",path+".code","Phase codes must be unique");
      if(p.name()==null||p.name().isBlank()||p.name().length()>200)issue(issues,"INVALID_PHASE_NAME",path+".name","Phase name must contain 1–200 characters");
      if(p.durationMs()==null||p.durationMs()<1000)issue(issues,"INVALID_PHASE_DURATION",path+".durationMs","Phase duration must be at least 1000 ms");
      List<BehaviorOverride> overrides=p.behaviorOverrides()==null?List.of():p.behaviorOverrides();Set<UUID> local=new HashSet<>();
      for(int j=0;j<overrides.size();j++){BehaviorOverride o=overrides.get(j);String op=path+".behaviorOverrides["+j+"]";if(o==null||o.signalId()==null){issue(issues,"SIGNAL_REQUIRED",op+".signalId","Signal is required");continue;}if(!local.add(o.signalId()))issue(issues,"DUPLICATE_OVERRIDE",op+".signalId","Signal override must be unique in a phase");behaviorSignalIds.add(o.signalId());if(o.action()==OverrideAction.REPLACE)validateBehavior(o.behavior(),op+".behavior",null,issues);else if(o.action()==OverrideAction.DISABLE&&o.behavior()!=null)issue(issues,"DISABLE_HAS_BEHAVIOR",op+".behavior","DISABLE override cannot include behavior");}
    }}
    for(UUID id:behaviorSignalIds){SignalInfo s=signals.get(id);if(s==null){issue(issues,"SIGNAL_NOT_IN_TARGET","signalId","Signal must belong to a selected machine");continue;}if(!"ACTIVE".equals(s.status()))issue(issues,"SIGNAL_NOT_ACTIVE","signalId","Signal must be active");
      for(int i=0;targets!=null&&i<targets.size();i++){MachineTarget t=targets.get(i);if(t!=null&&t.baseBehaviors()!=null)for(int j=0;j<t.baseBehaviors().size();j++){BaseBehavior b=t.baseBehaviors().get(j);if(b!=null&&id.equals(b.signalId()))validateBehavior(b.behavior(),"machineTargets["+i+"].baseBehaviors["+j+"].behavior",s,issues);}}
      for(int i=0;phases!=null&&i<phases.size();i++){Phase p=phases.get(i);if(p!=null&&p.behaviorOverrides()!=null)for(int j=0;j<p.behaviorOverrides().size();j++){BehaviorOverride o=p.behaviorOverrides().get(j);if(o!=null&&id.equals(o.signalId())&&o.action()==OverrideAction.REPLACE)validateBehavior(o.behavior(),"phases["+i+"].behaviorOverrides["+j+"].behavior",s,issues);}}
    }
    // Every phase must resolve at least one behavior across target machines.
    if(phases!=null&&targets!=null)for(int i=0;i<phases.size();i++){Phase p=phases.get(i);if(p==null)continue;boolean generated=false;for(MachineTarget t:targets){if(t==null)continue;Set<UUID> effective=new HashSet<>();if(t.baseBehaviors()!=null)for(BaseBehavior b:t.baseBehaviors())if(b!=null&&b.signalId()!=null)effective.add(b.signalId());if(p.behaviorOverrides()!=null)for(BehaviorOverride o:p.behaviorOverrides())if(o!=null&&o.signalId()!=null){if(o.action()==OverrideAction.DISABLE)effective.remove(o.signalId());else if(o.action()==OverrideAction.REPLACE)effective.add(o.signalId());}if(!effective.isEmpty())generated=true;}if(!generated)issue(issues,"PHASE_HAS_NO_BEHAVIOR","phases["+i+"]","Each phase must resolve at least one generated signal behavior");}
    int errors=(int)issues.stream().filter(x->x.severity()==Severity.ERROR).count();int warnings=issues.size()-errors;return new ValidationResult(errors==0,errors,warnings,issues);
  }

  private void validateBehavior(JsonNode b,String path,SignalInfo signal,List<ValidationIssue> issues){
    if(b==null||!b.isObject()){issue(issues,"BEHAVIOR_REQUIRED",path,"Behavior configuration is required");return;}
    String model=b.path("model").asText("");Set<String> numeric=Set.of("UNIFORM","NORMAL","RAMP","SINE");Set<String> allowed=Set.of("CONSTANT","UNIFORM","NORMAL","RAMP","SINE","COUNTER_RATE","PULSE","BERNOULLI","SEQUENCE");
    if(!allowed.contains(model)){issue(issues,"UNSUPPORTED_BEHAVIOR_MODEL",path+".model","Behavior model is not supported");return;}
    long interval=b.path("generationIntervalMs").asLong(0);if(interval<1000)issue(issues,"INVALID_GENERATION_INTERVAL",path+".generationIntervalMs","Generation interval must be at least 1000 ms");
    double missing=b.path("missingProbability").asDouble(0);if(!Double.isFinite(missing)||missing<0||missing>1)issue(issues,"INVALID_MISSING_PROBABILITY",path+".missingProbability","Missing probability must be between 0 and 1");
    String quality=b.path("sourceQuality").asText("GOOD");if(!Set.of("GOOD","UNCERTAIN","BAD").contains(quality))issue(issues,"INVALID_SOURCE_QUALITY",path+".sourceQuality","Source quality is invalid");
    if(numeric.contains(model)){double noise=b.path("noiseStandardDeviation").asDouble(0);if(!Double.isFinite(noise)||noise<0)issue(issues,"INVALID_NOISE",path+".noiseStandardDeviation","Noise must be finite and non-negative");}
    Map<String,String[]> required=Map.of("CONSTANT",new String[]{"value"},"UNIFORM",new String[]{"min","max"},"NORMAL",new String[]{"mean","standardDeviation"},"RAMP",new String[]{"from","to","durationMs","repeat"},"SINE",new String[]{"center","amplitude","periodMs"},"COUNTER_RATE",new String[]{"initialValue","incrementPerSecond"},"PULSE",new String[]{"onDurationMs","offDurationMs","initialValue"},"BERNOULLI",new String[]{"trueProbability"},"SEQUENCE",new String[]{"values","dwellMs","repeat"});
    for(String f:required.get(model))if(!b.has(f)||b.get(f).isNull())issue(issues,"BEHAVIOR_FIELD_REQUIRED",path+"."+f,"Required model parameter is missing");
    for(Iterator<Map.Entry<String,JsonNode>> it=b.fields();it.hasNext();){var e=it.next();JsonNode v=e.getValue();if(v.isNumber()&&!Double.isFinite(v.doubleValue()))issue(issues,"NON_FINITE_PARAMETER",path+"."+e.getKey(),"Numeric parameters must be finite");}
    Set<String> numericFields=switch(model){case "UNIFORM"->Set.of("min","max");case "NORMAL"->Set.of("mean","standardDeviation","clampMin","clampMax");case "RAMP"->Set.of("from","to");case "SINE"->Set.of("center","amplitude","phaseRadians");case "COUNTER_RATE"->Set.of("initialValue","incrementPerSecond","resetThreshold","resetValue");case "BERNOULLI"->Set.of("trueProbability");default->Set.of();};
    if(model.equals("CONSTANT")&&b.has("value")&&b.get("value").isNumber()&&!Double.isFinite(b.get("value").doubleValue()))issue(issues,"NON_FINITE_PARAMETER",path+".value","Numeric parameters must be finite");
    for(String f:numericFields)if(b.has(f)&&!b.get(f).isNumber())issue(issues,"INVALID_PARAMETER_TYPE",path+"."+f,"Parameter must be numeric");
    for(String f:List.of("durationMs","periodMs","onDurationMs","offDurationMs","dwellMs"))if(b.has(f)&&!b.get(f).isIntegralNumber())issue(issues,"INVALID_PARAMETER_TYPE",path+"."+f,"Duration must be an integer");
    for(String f:List.of("repeat"))if(b.has(f)&&!b.get(f).isBoolean())issue(issues,"INVALID_PARAMETER_TYPE",path+"."+f,"Parameter must be boolean");
    if(model.equals("PULSE")&&b.has("initialValue")&&!b.get("initialValue").isBoolean())issue(issues,"INVALID_PARAMETER_TYPE",path+".initialValue","Parameter must be boolean");
    if(model.equals("SEQUENCE")&&b.has("values")&&b.get("values").isArray())for(int i=0;i<b.get("values").size();i++)if(!b.get("values").get(i).isTextual()||b.get("values").get(i).asText().length()>500)issue(issues,"INVALID_SEQUENCE_VALUE",path+".values["+i+"]","Sequence values must be strings up to 500 characters");
    range(b,"min","max",model.equals("UNIFORM"),path,issues);range(b,"clampMin","clampMax",false,path,issues);
    if(b.has("standardDeviation")&&b.path("standardDeviation").asDouble()<=0)issue(issues,"INVALID_STANDARD_DEVIATION",path+".standardDeviation","Standard deviation must be positive");
    for(String f:List.of("amplitude","incrementPerSecond","trueProbability"))if(b.has(f)&&b.path(f).asDouble()<0)issue(issues,"INVALID_PARAMETER_RANGE",path+"."+f,"Parameter must be non-negative");
    if(b.has("trueProbability")&&b.path("trueProbability").asDouble()>1)issue(issues,"INVALID_PARAMETER_RANGE",path+".trueProbability","Probability must be at most 1");
    if(model.equals("COUNTER_RATE")&&b.has("resetThreshold")&&b.path("resetThreshold").asDouble()<1d)
      issue(issues,"INVALID_RESET_THRESHOLD",path+".resetThreshold","Reset threshold must be at least 1");
    if(model.equals("PULSE")&&b.has("onDurationMs")&&b.has("offDurationMs")){
      long on=b.path("onDurationMs").asLong(),off=b.path("offDurationMs").asLong();
      if(on>0&&off>0&&on>Long.MAX_VALUE-off)issue(issues,"INVALID_PULSE_PERIOD",path+".offDurationMs","Combined pulse period exceeds the supported range");
    }
    for(String f:List.of("durationMs","periodMs","onDurationMs","offDurationMs","dwellMs"))if(b.has(f)&&b.path(f).asLong(0)<1000)issue(issues,"INVALID_DURATION",path+"."+f,"Duration must be at least 1000 ms");
    if(model.equals("SEQUENCE")&&(!b.path("values").isArray()||b.path("values").isEmpty()))issue(issues,"EMPTY_SEQUENCE",path+".values","Sequence must contain at least one value");
    if(signal!=null){String type=signal.valueType(),kind=signal.signalKind();boolean compatible=switch(model){case "CONSTANT"->valueMatches(b.get("value"),type);case "UNIFORM","NORMAL","RAMP","SINE"->Set.of("DOUBLE","LONG").contains(type)&&"GAUGE".equals(kind);case "COUNTER_RATE"->Set.of("DOUBLE","LONG").contains(type)&&"COUNTER".equals(kind);case "PULSE","BERNOULLI"->"BOOLEAN".equals(type);case "SEQUENCE"->"STRING".equals(type);default->false;};if(!compatible)issue(issues,"BEHAVIOR_SIGNAL_TYPE_MISMATCH",path+".model","Behavior model is incompatible with signal type and kind");}
  }
  private boolean valueMatches(JsonNode v,String type){if(v==null)return false;return switch(type){case "DOUBLE"->v.isNumber()&&Double.isFinite(v.doubleValue());case "LONG"->v.isIntegralNumber()&&v.canConvertToLong();case "BOOLEAN"->v.isBoolean();case "STRING"->v.isTextual()&&v.asText().length()<=500;default->false;};}
  private void range(JsonNode b,String low,String high,boolean both,String path,List<ValidationIssue> issues){if(both&&b.has(low)&&b.has(high)&&b.path(low).asDouble()>b.path(high).asDouble())issue(issues,"INVALID_PARAMETER_RANGE",path+"."+low,"Minimum must not exceed maximum");}
  private void issue(List<ValidationIssue> issues,String code,String path,String message){issues.add(new ValidationIssue(Severity.ERROR,code,path,message,null,null));}
  private record MachineInfo(UUID enterpriseId,String status,UUID lineId,String lineStatus){}
  private record SignalInfo(UUID machineId,String valueType,String signalKind,String status){}
}
