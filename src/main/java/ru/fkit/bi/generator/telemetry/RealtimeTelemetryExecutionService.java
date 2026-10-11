package ru.fkit.bi.generator.telemetry;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Execution-side boundary used by realtime generators to publish one stable, retryable batch. */
@Service
public class RealtimeTelemetryExecutionService {
  private final TelemetrySink sink;

  public RealtimeTelemetryExecutionService(TelemetrySink sink) {
    this.sink = Objects.requireNonNull(sink);
  }

  public TelemetryWriteResult publish(String producerId, String stableBatchId, long sequence, UUID runId,
      List<TelemetryMeasurement> generatedMeasurements) {
    return publish(producerId, stableBatchId, sequence, runId, TelemetryBatch.Mode.REALTIME, generatedMeasurements);
  }

  public TelemetryWriteResult publish(String producerId, String stableBatchId, long sequence, UUID runId,
      TelemetryBatch.Mode mode, List<TelemetryMeasurement> generatedMeasurements) {
    return sink.accept(new TelemetryBatch(producerId, stableBatchId, sequence, runId, mode, generatedMeasurements));
  }
}
