package ru.fkit.bi.generator.telemetry;

public final class TelemetryRetryableException extends RuntimeException {
  public TelemetryRetryableException(String message) { super(message); }
  public TelemetryRetryableException(String message, Throwable cause) { super(message, cause); }
}
