package ru.fkit.bi.generator.scenario;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import ru.fkit.bi.generator.scenario.ScenarioModels.ValidationResult;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ScenarioValidationException extends RuntimeException {
  private final ValidationResult validation;
  public ScenarioValidationException(ValidationResult validation){super("Scenario validation failed");this.validation=validation;}
  public ValidationResult validation(){return validation;}
}
