package ru.fkit.bi.generator.telemetry;

/** Writer receipt needed by Run counters; a dropped realtime batch is not a successful write. */
public record TelemetryWriteResult(int writtenMeasurements, int droppedMeasurements) {
  public static TelemetryWriteResult committed(int count) { return new TelemetryWriteResult(count, 0); }
  public static TelemetryWriteResult dropped(int count) { return new TelemetryWriteResult(0, count); }
}
