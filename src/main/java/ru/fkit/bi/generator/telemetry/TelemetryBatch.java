package ru.fkit.bi.generator.telemetry;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable wire envelope for the versioned TELEMETRY_BATCH_V1 contract. */
public record TelemetryBatch(
    String producerId,
    String producerBatchId,
    long producerSequence,
    UUID generatorRunId,
    Mode mode,
    List<TelemetryMeasurement> measurements) {

  public TelemetryBatch {
    Objects.requireNonNull(producerId, "producerId");
    Objects.requireNonNull(producerBatchId, "producerBatchId");
    Objects.requireNonNull(mode, "mode");
    measurements = List.copyOf(Objects.requireNonNull(measurements, "measurements"));
  }

  public enum Mode {
    REALTIME(1), HISTORICAL(2);
    private final short code;
    Mode(int code) { this.code = (short) code; }
    public short code() { return code; }
  }
}
