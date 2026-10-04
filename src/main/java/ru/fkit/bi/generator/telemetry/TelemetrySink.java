package ru.fkit.bi.generator.telemetry;

/** Boundary for telemetry transport; JDBC is the demo adapter and can later be replaced. */
public interface TelemetrySink { void accept(TelemetryBatch batch); }
