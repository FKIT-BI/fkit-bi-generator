package ru.fkit.bi.generator.telemetry;

import java.util.List;

public record TelemetryBatch(List<TelemetryMeasurement> measurements) {}
