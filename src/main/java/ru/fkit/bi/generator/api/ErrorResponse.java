package ru.fkit.bi.generator.api;

import java.util.List;
import java.util.Map;

public record ErrorResponse(
    String code,
    String message,
    String traceId,
    List<FieldError> fieldErrors,
    Map<String, Object> details) {

  public ErrorResponse {
    fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public record FieldError(String path, String code, String message) {}
}
