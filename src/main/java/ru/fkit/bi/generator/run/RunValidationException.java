package ru.fkit.bi.generator.run;

import ru.fkit.bi.generator.run.RunModels.ValidationResult;

public class RunValidationException extends RuntimeException {
  private final ValidationResult validation;

  public RunValidationException(ValidationResult validation) {
    super("Run validation failed");
    this.validation = validation;
  }

  public ValidationResult validation() { return validation; }
}
