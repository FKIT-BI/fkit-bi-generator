package ru.fkit.bi.generator.run;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.fkit.bi.generator.run.RunModels.*;

@RestController
@RequestMapping("/api/v1/runs")
@Validated
public class RunController {
  private final RunService service;

  public RunController(RunService service) { this.service = service; }

  @GetMapping
  public Page list(@RequestParam UUID enterpriseId,
      @RequestParam(required = false) UUID scenarioId,
      @RequestParam(required = false) UUID machineId,
      @RequestParam(required = false) Mode mode,
      @RequestParam(required = false) List<Status> status,
      @RequestParam(required = false) String requestedFrom,
      @RequestParam(required = false) String requestedTo,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return service.list(enterpriseId, scenarioId, machineId, mode, status,
        requestedFrom, requestedTo, cursor, limit);
  }

  @PostMapping("/validate")
  public ValidationResult validate(@Valid @RequestBody CreateRequest request) {
    return service.validate(request);
  }

  @PostMapping
  public ResponseEntity<Detail> create(Authentication authentication,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateRequest request) {
    Detail detail = service.create(authentication.getName(), idempotencyKey, request);
    return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + detail.id())).body(detail);
  }

  @GetMapping("/{runId}")
  public Detail get(@PathVariable UUID runId) { return service.get(runId); }

  @PostMapping("/{runId}/stop")
  public ResponseEntity<Detail> stop(@PathVariable UUID runId) {
    RunService.StopResult result = service.stop(runId);
    return ResponseEntity.status(result.httpStatus()).body(result.detail());
  }

  @PostMapping("/{runId}/retry")
  public ResponseEntity<Detail> retry(@PathVariable UUID runId) {
    Detail detail = service.retry(runId);
    return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + detail.id())).body(detail);
  }
}
