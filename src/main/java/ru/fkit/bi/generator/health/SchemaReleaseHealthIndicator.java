package ru.fkit.bi.generator.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("schemaRelease")
public class SchemaReleaseHealthIndicator implements HealthIndicator {
  private final JdbcTemplate jdbcTemplate;
  private final String expectedRelease;

  public SchemaReleaseHealthIndicator(
      JdbcTemplate jdbcTemplate,
      @Value("${fkit.schema.expected-release:002-authentication}") String expectedRelease) {
    this.jdbcTemplate = jdbcTemplate;
    this.expectedRelease = expectedRelease;
  }

  @Override
  public Health health() {
    final List<String> releases;
    try {
      releases = jdbcTemplate.queryForList(
          "SELECT release FROM equipment.schema_release WHERE singleton = TRUE", String.class);
    } catch (DataAccessException exception) {
      return Health.down().withDetail("reason", "RELEASE_CHECK_FAILED").build();
    }

    if (releases.isEmpty()) {
      return Health.down()
          .withDetail("reason", "RELEASE_MISSING")
          .withDetail("expectedRelease", expectedRelease)
          .build();
    }
    String actualRelease = releases.getFirst();
    if (!expectedRelease.equals(actualRelease) || releases.size() != 1) {
      return Health.down()
          .withDetail("reason", "RELEASE_MISMATCH")
          .withDetail("expectedRelease", expectedRelease)
          .withDetail("actualRelease", actualRelease)
          .build();
    }
    return Health.up().withDetail("release", actualRelease).build();
  }
}
