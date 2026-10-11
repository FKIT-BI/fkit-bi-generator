package ru.fkit.bi.generator.telemetry;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical TELEMETRY_BATCH_V1 SHA-256 encoding shared with Analytics. */
final class TelemetryBatchHasher {
  private static final byte[] MAGIC = "TELEMETRY_BATCH_V1".getBytes(StandardCharsets.US_ASCII);

  byte[] hash(TelemetryBatch batch) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        out.write(MAGIC);
        writeString(out, batch.producerId());
        writeString(out, batch.producerBatchId());
        out.writeLong(batch.producerSequence());
        writeOptionalUuid(out, batch.generatorRunId());
        out.writeShort(batch.mode().code());
        out.writeInt(batch.measurements().size());
        for (var measurement : batch.measurements()) writeMeasurement(out, measurement);
      }
      return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Unable to encode telemetry batch", e);
    }
  }

  static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

  private static void writeMeasurement(DataOutputStream out, TelemetryMeasurement measurement) throws IOException {
    writeUuid(out, measurement.signalId());
    Instant observedAt = measurement.observedAt();
    if (observedAt.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("observedAt must have millisecond precision");
    }
    out.writeLong(observedAt.toEpochMilli());
    out.writeByte(measurement.valueTypeCode());
    switch (measurement.valueTypeCode()) {
      case 1 -> {
        double value = (Double) measurement.value();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Double measurement must be finite");
        out.writeLong(Double.doubleToRawLongBits(value));
      }
      case 2 -> out.writeLong((Long) measurement.value());
      case 3 -> out.writeByte((Boolean) measurement.value() ? 1 : 0);
      case 4 -> writeString(out, (String) measurement.value());
      default -> throw new IllegalArgumentException("Unknown telemetry type");
    }
    out.writeShort(measurement.sourceQuality());
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(encoded.length);
    out.write(encoded);
  }

  private static void writeOptionalUuid(DataOutputStream out, UUID uuid) throws IOException {
    out.writeByte(uuid == null ? 0 : 1);
    if (uuid != null) writeUuid(out, uuid);
  }

  private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
    out.writeLong(uuid.getMostSignificantBits());
    out.writeLong(uuid.getLeastSignificantBits());
  }
}
