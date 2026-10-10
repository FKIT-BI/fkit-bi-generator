package ru.fkit.bi.generator.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.CookieValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ru.fkit.bi.generator.api.ErrorResponse> methodValidation(
      HandlerMethodValidationException exception, HttpServletRequest request) {
    List<ru.fkit.bi.generator.api.ErrorResponse.FieldError> fields = exception.getParameterValidationResults().stream()
        .flatMap(result -> result.getResolvableErrors().stream().map(ignored ->
            new ru.fkit.bi.generator.api.ErrorResponse.FieldError(parameterPath(result.getMethodParameter()), "INVALID", "Value is invalid")))
        .distinct()
        .toList();
    return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Request validation failed", fields);
  }

  private ResponseEntity<ru.fkit.bi.generator.api.ErrorResponse> frameworkError(
      org.springframework.web.ErrorResponse exception, HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    String code = switch (status) {
      case BAD_REQUEST -> "MALFORMED_REQUEST";
      case NOT_FOUND -> "NOT_FOUND";
      case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
      case CONFLICT -> "CONFLICT";
      default -> "HTTP_" + status.value() + "_" + status.name();
    };
    return error(request, status, code, status.getReasonPhrase(), List.of());
  }

  private String parameterPath(MethodParameter parameter) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null) return annotationName(requestParam.name(), requestParam.value(), parameter);
    PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
    if (pathVariable != null) return annotationName(pathVariable.name(), pathVariable.value(), parameter);
    RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
    if (requestHeader != null) return annotationName(requestHeader.name(), requestHeader.value(), parameter);
    CookieValue cookieValue = parameter.getParameterAnnotation(CookieValue.class);
    if (cookieValue != null) return annotationName(cookieValue.name(), cookieValue.value(), parameter);
    return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
  }

  private String annotationName(String name, String value, MethodParameter parameter) {
    if (!name.isBlank()) return name;
    if (!value.isBlank()) return value;
    return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
    if (exception instanceof org.springframework.web.ErrorResponse frameworkError) {
      return frameworkError(frameworkError, request);
    }
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
