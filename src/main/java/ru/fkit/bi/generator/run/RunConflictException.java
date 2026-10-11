package ru.fkit.bi.generator.run;

import java.util.Map;

public class RunConflictException extends RuntimeException {
  private final String code;
  private final Map<String, Object> details;

  public RunConflictException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public String code() { return code; }
  public Map<String, Object> details() { return details; }
}
