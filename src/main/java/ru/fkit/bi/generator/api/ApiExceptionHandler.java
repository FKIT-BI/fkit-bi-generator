package ru.fkit.bi.generator.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson(HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is invalid", List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<ErrorResponse.FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorResponse.FieldError(error.getField(), "INVALID", "Value is invalid"))
        .toList();
    return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Request validation failed", fields);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(HttpServletRequest request) {
    ResponseEntity<ErrorResponse> response = error(
        request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", List.of());
    LOGGER.error("event=api_error code={} traceId={}", response.getBody().code(), response.getBody().traceId());
    return response;
  }

  private ResponseEntity<ErrorResponse> error(
      HttpServletRequest request, HttpStatus status, String code, String message, List<ErrorResponse.FieldError> fields) {
    Object attribute = request.getAttribute(TraceIdFilter.ATTRIBUTE);
    String traceId = attribute instanceof String value ? value : "unknown";
    return ResponseEntity.status(status).body(new ErrorResponse(code, message, traceId, fields, Map.of()));
  }
}
