package ru.fkit.bi.generator.scenario;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.generator.scenario.ScenarioModels.*;

@RestController
@RequestMapping("/api/v1/scenarios")
@Validated
public class ScenarioController {
  private final ScenarioService service;
  public ScenarioController(ScenarioService service){this.service=service;}

  @GetMapping
  public Page list(@RequestParam @NotNull UUID enterpriseId,
      @RequestParam(required=false) Status status,
      @RequestParam(required=false) String search,
      @RequestParam(defaultValue="0") @Min(0) int page,
      @RequestParam(defaultValue="50") @Min(1) @Max(200) int size){return service.list(enterpriseId,status,search,page,size);}

  @PostMapping
  public ResponseEntity<Detail> create(@Valid @RequestBody CreateRequest request){
    Detail detail=service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/scenarios/"+detail.id())).body(detail);
  }

  @PostMapping("/{scenarioId}/copy")
  public ResponseEntity<Detail> copy(@PathVariable UUID scenarioId,@Valid @RequestBody CopyRequest request){
    Detail detail=service.copy(scenarioId,request);
    return ResponseEntity.created(URI.create("/api/v1/scenarios/"+detail.id())).body(detail);
  }

  @PostMapping("/validate")
  public ValidationResult validate(@Valid @RequestBody CreateRequest request){return service.validate(request);}

  @GetMapping("/{scenarioId}")
  public Detail get(@PathVariable UUID scenarioId){return service.get(scenarioId);}

  @PutMapping("/{scenarioId}")
  public Detail replace(@PathVariable UUID scenarioId,@Valid @RequestBody UpdateRequest request){return service.replace(scenarioId,request);}

  @PutMapping("/{scenarioId}/status")
  public Detail status(@PathVariable UUID scenarioId,@Valid @RequestBody StatusRequest request){return service.status(scenarioId,request.status());}
}
