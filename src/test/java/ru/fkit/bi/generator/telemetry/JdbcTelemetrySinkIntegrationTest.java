package ru.fkit.bi.generator.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class JdbcTelemetrySinkIntegrationTest {
  private static final UUID MACHINE = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID DOUBLE_SIGNAL = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID LONG_SIGNAL = UUID.fromString("00000000-0000-0000-0000-000000000202");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private JdbcTemplate jdbc;
  private JdbcTelemetrySink sink;
  private RealtimeTelemetryExecutionService realtimeExecution;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS telemetry CASCADE");
      statement.execute("DROP SCHEMA IF EXISTS equipment CASCADE");
      statement.execute("CREATE SCHEMA equipment");
      statement.execute("CREATE SCHEMA telemetry");
      statement.execute("CREATE TABLE equipment.machine(id UUID PRIMARY KEY)");
      statement.execute("CREATE TABLE equipment.telemetry_signal(id UUID PRIMARY KEY,machine_id UUID NOT NULL,"
          + "value_type VARCHAR(16) NOT NULL,valid_min DOUBLE PRECISION,valid_max DOUBLE PRECISION,status VARCHAR(16) NOT NULL)");
      statement.execute("CREATE TABLE telemetry.signal_usage(signal_id UUID PRIMARY KEY,"
          + "first_used_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),"
          + "first_observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),"
          + "first_received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())");
      statement.execute("CREATE TABLE telemetry.ingest_batch(id BIGSERIAL PRIMARY KEY,producer_id VARCHAR(128) NOT NULL,"
          + "producer_batch_id VARCHAR(128) NOT NULL,producer_sequence BIGINT NOT NULL,payload_hash BYTEA NOT NULL,"
          + "generator_run_id UUID,mode SMALLINT NOT NULL,measurement_count INTEGER NOT NULL,"
          + "min_observed_at TIMESTAMPTZ NOT NULL,max_observed_at TIMESTAMPTZ NOT NULL,"
          + "UNIQUE(producer_id,producer_batch_id))");
      statement.execute("CREATE TABLE telemetry.raw_partition_registry(partition_day DATE PRIMARY KEY,status VARCHAR(16) NOT NULL)");
      statement.execute("CREATE FUNCTION telemetry.raw_partition_day_lock_key(DATE) RETURNS BIGINT LANGUAGE SQL IMMUTABLE "
          + "AS $$ SELECT hashtextextended('fkit-bi:raw-day:' || $1::text,0) $$");
      statement.execute("CREATE TABLE telemetry.raw_measurement(ingest_batch_id BIGINT NOT NULL,ordinal INTEGER NOT NULL,"
          + "signal_id UUID NOT NULL,observed_at TIMESTAMPTZ NOT NULL,value_double DOUBLE PRECISION,value_long BIGINT,"
          + "value_boolean BOOLEAN,value_text VARCHAR(500),source_quality SMALLINT NOT NULL,validation_flags INTEGER NOT NULL)");
      statement.execute("CREATE TABLE telemetry.recalculation_hint(id BIGSERIAL PRIMARY KEY,ingest_batch_id BIGINT NOT NULL,"
          + "machine_id UUID NOT NULL,from_observed_at TIMESTAMPTZ NOT NULL,to_observed_at TIMESTAMPTZ NOT NULL)");
      statement.execute("INSERT INTO equipment.machine VALUES ('" + MACHINE + "')");
      statement.execute("INSERT INTO equipment.telemetry_signal VALUES ('" + DOUBLE_SIGNAL + "','" + MACHINE
          + "','DOUBLE',0,1000,'ACTIVE'),('" + LONG_SIGNAL + "','" + MACHINE + "','LONG',NULL,NULL,'ACTIVE')");
      statement.execute("INSERT INTO telemetry.raw_partition_registry VALUES ('" + LocalDate.now(ZoneOffset.UTC) + "','ACTIVE')");
    }

    var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    sink = new JdbcTelemetrySink(jdbc, new DataSourceTransactionManager(dataSource), new SimpleMeterRegistry(), 5000);
    realtimeExecution = new RealtimeTelemetryExecutionService(sink);
  }

  @Test
  void identicalRetryIsIdempotentAndDifferentPayloadConflicts() {
    TelemetryBatch batch = batch(12.5d);
    realtimeExecution.publish(batch.producerId(), batch.producerBatchId(), batch.producerSequence(),
        batch.generatorRunId(), batch.measurements());
    realtimeExecution.publish(batch.producerId(), batch.producerBatchId(), batch.producerSequence(),
        batch.generatorRunId(), batch.measurements());

    assertEquals(1, count("telemetry.ingest_batch"));
    assertEquals(1, count("telemetry.raw_measurement"));
    assertEquals(1, count("telemetry.signal_usage"));
    assertEquals(1, count("telemetry.recalculation_hint"));
    assertThrows(TelemetryConflictException.class, () -> sink.accept(batch(13.5d)));
  }

  @Test
  void unavailablePartitionRollsBackHeaderAndAllRows() {
    jdbc.update("UPDATE telemetry.raw_partition_registry SET status='RETIRING'");
    sink.accept(batch(12.5d));
    assertEquals(0, count("telemetry.ingest_batch"));
    assertEquals(0, count("telemetry.raw_measurement"));
    assertEquals(0, count("telemetry.signal_usage"));
    assertEquals(0, count("telemetry.recalculation_hint"));
  }

  @Test
  void invalidLaterMeasurementRollsBackTheWholeBatch() {
    TelemetryBatch batch = new TelemetryBatch("test", "batch-2", 2, null, TelemetryBatch.Mode.REALTIME,
        java.util.List.of(
            new TelemetryMeasurement(DOUBLE_SIGNAL, Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 12.5d),
            new TelemetryMeasurement(LONG_SIGNAL, Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 4.5d)));
    assertThrows(TelemetryValidationException.class, () -> sink.accept(batch));
    assertEquals(0, count("telemetry.ingest_batch"));
    assertEquals(0, count("telemetry.raw_measurement"));
    assertEquals(0, count("telemetry.signal_usage"));
    assertEquals(0, count("telemetry.recalculation_hint"));
  }

  private TelemetryBatch batch(Object value) {
    Instant observed = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    return new TelemetryBatch("test", "batch-1", 1, UUID.randomUUID(), TelemetryBatch.Mode.REALTIME,
        java.util.List.of(new TelemetryMeasurement(DOUBLE_SIGNAL, observed, value)));
  }

  private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
