package ru.fkit.bi.generator.epic6;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Contract inventory guard for the public operation surface, not a substitute for API behavior tests. */
class GeneratorOpenApiContractTest {
  private static final Set<String> OPERATIONS = Set.of(
      "listScenarios", "createScenario", "validateScenarioDraft", "getScenario", "updateScenario",
      "updateScenarioStatus", "listRuns", "createRun", "validateRun", "getRun", "stopRun", "retryRun");

  @Test
  void openApiRetainsAllTwelveSecuredOperationsAndSharedAuthErrors() throws Exception {
    String yaml = Files.readString(Path.of("docs/contracts/generator-api-draft.yaml"));
    Matcher matcher = Pattern.compile("(?m)^\\s+operationId: ([A-Za-z0-9]+)\\s*$").matcher(yaml);
    Set<String> actual = new HashSet<>();
    while (matcher.find()) assertTrue(actual.add(matcher.group(1)), "duplicate operationId " + matcher.group(1));
    assertEquals(OPERATIONS, actual);
    assertTrue(yaml.contains("security:\n  - bearerAuth: []"), "API must require the shared JWT bearer scheme");
    assertTrue(yaml.contains("bearerAuth:\n      type: http\n      scheme: bearer\n      bearerFormat: JWT"));
    assertEquals(12, occurrences(yaml, "'401': { $ref: '#/components/responses/Unauthorized' }"));
    assertEquals(12, occurrences(yaml, "'403': { $ref: '#/components/responses/Forbidden' }"));
    for (String response : Set.of("Unauthorized", "Forbidden", "BadRequest", "NotFound", "Conflict", "UnprocessableEntity", "InternalError")) {
      assertTrue(yaml.contains("    " + response + ":"), "missing shared response " + response);
    }
  }

  @Test
  void descriptionLimitIsConsistentWithDatabaseAndDomain() throws Exception {
    String yaml = Files.readString(Path.of("docs/contracts/generator-api-draft.yaml"));
    Path migrationPath = Path.of(".ci/fkit-bi-analytics/src/main/resources/db/changelog/releases/013-generator-run-control/001-generator-run-control.sql");
    if (!Files.isRegularFile(migrationPath)) {
      migrationPath = Path.of("../fkit-bi-analytics/src/main/resources/db/changelog/releases/013-generator-run-control/001-generator-run-control.sql");
    }
    String migration = Files.readString(migrationPath);
    String validator = Files.readString(Path.of("src/main/java/ru/fkit/bi/generator/scenario/ScenarioValidator.java"));
    assertEquals(2, occurrences(yaml, "description: { type: [string, 'null'], maxLength: 2000 }"));
    assertTrue(migration.contains("description VARCHAR(2000)"));
    assertTrue(validator.contains("description.length() > 2000"));
  }

  private int occurrences(String value, String needle) {
    int count = 0, from = 0;
    while ((from = value.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
    return count;
  }
}
