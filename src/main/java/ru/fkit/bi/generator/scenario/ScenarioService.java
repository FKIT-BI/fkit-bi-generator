package ru.fkit.bi.generator.scenario;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.fkit.bi.generator.scenario.ScenarioModels.*;

@Service
public class ScenarioService {
  private final ScenarioRepository repository;
  private final ScenarioValidator validator;
  public ScenarioService(ScenarioRepository repository, ScenarioValidator validator) { this.repository=repository;this.validator=validator; }

  public Page list(UUID enterpriseId, Status status, String search, int page, int size) {
    if(page<0||size<1||size>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page or size");
    return repository.list(enterpriseId,status==null?Status.ACTIVE:status,search,page,size);
  }
  public ValidationResult validate(CreateRequest request) { return validator.validate(request.enterpriseId(),request.code(),request.name(),request.description(),request.cycleMode(),request.machineTargets(),request.phases()); }
  @Transactional
  public Detail create(CreateRequest request) {
    ValidationResult result=validate(request);
    if(result.issues().stream().anyMatch(i -> Set.of("ENTERPRISE_REQUIRED","ENTERPRISE_NOT_FOUND","INVALID_CODE","INVALID_NAME","DESCRIPTION_TOO_LONG","CYCLE_MODE_REQUIRED","MACHINE_TARGETS_REQUIRED","PHASES_REQUIRED","MACHINE_REQUIRED","MACHINE_NOT_FOUND","MACHINE_ENTERPRISE_MISMATCH").contains(i.code())))
      throw new ScenarioValidationException(result);
    try{return repository.insert(request,result);}catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Scenario code already exists for enterprise");}
  }
  @Transactional
  public Detail copy(UUID id, CopyRequest request) {
    Detail source=repository.get(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Scenario not found"));
    String description=request.description()==null?source.description():request.description();
    return create(new CreateRequest(source.enterpriseId(),request.code(),request.name(),description,source.cycleMode(),source.machineTargets(),source.phases()));
  }
  public Detail get(UUID id) {
    Detail d=repository.get(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Scenario not found"));
    ValidationResult v=validator.validate(d.enterpriseId(),d.code(),d.name(),d.description(),d.cycleMode(),d.machineTargets(),d.phases());
    return copy(d,d.status(),v);
  }
  @Transactional
  public Detail replace(UUID id, UpdateRequest request) {
    Detail before=repository.get(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Scenario not found"));
    ValidationResult v=validator.validate(before.enterpriseId(),before.code(),request.name(),request.description(),request.cycleMode(),request.machineTargets(),request.phases());
    if(v.issues().stream().anyMatch(i -> Set.of("INVALID_NAME","DESCRIPTION_TOO_LONG","CYCLE_MODE_REQUIRED","MACHINE_TARGETS_REQUIRED","PHASES_REQUIRED").contains(i.code())))throw new ScenarioValidationException(v);
    if(before.status()==Status.ACTIVE&&!v.valid())throw new ScenarioValidationException(v);
    try{return repository.replace(id,request,v).map(d->copy(d,d.status(),v)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Scenario not found"));}
    catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Scenario definition conflicts with stored data");}
  }
  @Transactional
  public Detail status(UUID id, Status status) {
    Detail before=repository.get(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Scenario not found"));
    ValidationResult v=validator.validate(before.enterpriseId(),before.code(),before.name(),before.description(),before.cycleMode(),before.machineTargets(),before.phases());
    if(status==Status.ACTIVE&&!v.valid())throw new ScenarioValidationException(v);
    return repository.setStatus(id,status,v).map(d->copy(d,status,v)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Scenario not found"));
  }
  private Detail copy(Detail d,Status status,ValidationResult v){return new Detail(d.id(),d.enterpriseId(),d.code(),d.name(),d.description(),status,d.cycleMode(),d.machineTargets(),d.phases(),v,d.createdAt(),d.updatedAt());}
}
