package ru.fkit.bi.generator.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryBatchHasherTest {
  private final TelemetryBatchHasher hasher = new TelemetryBatchHasher();

  @Test
  void hashesCanonicalBatchAndPreservesMeasurementOrder() {
    var first = batch(List.of(
        new TelemetryMeasurement(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-01-02T03:04:05.006Z"), 12.5d),
        new TelemetryMeasurement(UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Instant.parse("2026-01-02T03:04:06.000Z"), 9L)));
    var retry = batch(List.of(
        new TelemetryMeasurement(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-01-02T03:04:05.006Z"), 12.5d),
        new TelemetryMeasurement(UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Instant.parse("2026-01-02T03:04:06.000Z"), 9L)));
    var reordered = batch(List.of(first.measurements().get(1), first.measurements().get(0)));

    assertEquals(32, hasher.hash(first).length);
    assertEquals(TelemetryBatchHasher.hex(hasher.hash(first)), TelemetryBatchHasher.hex(hasher.hash(retry)));
    assertNotEquals(TelemetryBatchHasher.hex(hasher.hash(first)), TelemetryBatchHasher.hex(hasher.hash(reordered)));
  }

  @Test
  void rejectsNanInfinityAndSubmillisecondTimesBeforeHashing() {
    var signal = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(batch(List.of(
        new TelemetryMeasurement(signal, Instant.parse("2026-01-02T03:04:05Z"), Double.NaN)))));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(batch(List.of(
        new TelemetryMeasurement(signal, Instant.parse("2026-01-02T03:04:05Z"), Double.POSITIVE_INFINITY)))));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(batch(List.of(
        new TelemetryMeasurement(signal, Instant.parse("2026-01-02T03:04:05.000000001Z"), 1L)))));
  }

  private TelemetryBatch batch(List<TelemetryMeasurement> measurements) {
    return new TelemetryBatch("producer", "batch", 4,
        UUID.fromString("00000000-0000-0000-0000-000000000003"), TelemetryBatch.Mode.REALTIME, measurements);
  }
}
