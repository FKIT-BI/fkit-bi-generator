package ru.fkit.bi.generator.telemetry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcTelemetrySink implements TelemetrySink {
  JdbcTelemetrySink(JdbcTemplate jdbcTemplate) { }
  public void accept(TelemetryBatch batch) { /* Contracted batch INSERT follows the first telemetry migration. */ }
}
