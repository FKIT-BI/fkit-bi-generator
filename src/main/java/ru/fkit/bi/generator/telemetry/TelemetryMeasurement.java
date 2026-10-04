package ru.fkit.bi.generator.telemetry;

import java.time.Instant;

public record TelemetryMeasurement(String machineId, Instant measuredAt, double value) {}
