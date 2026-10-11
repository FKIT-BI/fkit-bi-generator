package ru.fkit.bi.generator.telemetry;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A single typed measurement. sourceQuality uses the shared 0/1/2 wire encoding. */
public record TelemetryMeasurement(UUID signalId, Instant observedAt, Object value, short sourceQuality) {
  public TelemetryMeasurement {
    Objects.requireNonNull(signalId, "signalId");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(value, "value");
    if (sourceQuality < 0 || sourceQuality > 2) {
      throw new IllegalArgumentException("sourceQuality must be GOOD(0), UNCERTAIN(1), or BAD(2)");
    }
  }

  public TelemetryMeasurement(UUID signalId, Instant observedAt, Object value) {
    this(signalId, observedAt, value, (short) 0);
  }

  public int valueTypeCode() {
    if (value instanceof Double) return 1;
    if (value instanceof Long) return 2;
    if (value instanceof Boolean) return 3;
    if (value instanceof String) return 4;
    throw new IllegalArgumentException("Unsupported telemetry value type: " + value.getClass().getName());
  }
}
