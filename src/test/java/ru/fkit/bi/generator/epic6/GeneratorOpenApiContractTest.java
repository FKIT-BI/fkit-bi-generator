package ru.fkit.bi.generator.epic6;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Contract inventory guard for the public operation surface, not a substitute for API behavior tests. */
class GeneratorOpenApiContractTest {
  private static final Set<String> OPERATIONS = Set.of(
      "listScenarios", "createScenario", "validateScenarioDraft", "getScenario", "updateScenario",
      "updateScenarioStatus", "copyScenario", "listRuns", "createRun", "validateRun", "getRun", "stopRun", "retryRun");

  @Test
  void openApiRetainsAllThirteenSecuredOperationsAndSharedAuthErrors() throws Exception {
    String yaml = Files.readString(Path.of("docs/contracts/generator-api-draft.yaml"));
    Matcher matcher = Pattern.compile("(?m)^\\s+operationId: ([A-Za-z0-9]+)\\s*$").matcher(yaml);
    Set<String> actual = new HashSet<>();
    while (matcher.find()) assertTrue(actual.add(matcher.group(1)), "duplicate operationId " + matcher.group(1));
    assertEquals(OPERATIONS, actual);
    assertTrue(yaml.contains("security:\n  - bearerAuth: []"), "API must require the shared JWT bearer scheme");
    assertTrue(yaml.contains("bearerAuth:\n      type: http\n      scheme: bearer\n      bearerFormat: JWT"));
    assertEquals(13, occurrences(yaml, "'401': { $ref: '#/components/responses/Unauthorized' }"));
    assertEquals(13, occurrences(yaml, "'403': { $ref: '#/components/responses/Forbidden' }"));
    assertDocumentedOperationsAndBodies(yaml);
    assertParameterExamples(yaml);
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
    assertEquals(3, occurrences(yaml, "description: { type: [string, 'null'], maxLength: 2000 }"));
    assertTrue(migration.contains("description VARCHAR(2000)"));
    assertTrue(validator.contains("description.length() > 2000"));
  }

  @Test
  void canonicalContractIsPackagedForStaticSwaggerUi() throws Exception {
    try (InputStream contract = getClass().getClassLoader().getResourceAsStream("static/openapi/generator-api-draft.yaml")) {
      assertTrue(contract != null, "canonical contract must be included in the application jar");
      String packaged = new String(contract.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(Files.readString(Path.of("docs/contracts/generator-api-draft.yaml")), packaged);
    }
  }

  private int occurrences(String value, String needle) {
    int count = 0, from = 0;
    while ((from = value.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
    return count;
  }

  private void assertDocumentedOperationsAndBodies(String yaml) {
    Matcher operations = Pattern.compile("(?ms)^      operationId: ([A-Za-z0-9]+)\\n(.*?)(?=^      operationId:|^components:)").matcher(yaml);
    while (operations.find()) {
      String operationId = operations.group(1);
      String operation = operations.group(2);
      assertTrue(operation.matches("(?sm).*^      description: .+"), "missing operation description: " + operationId);
      Matcher bodies = Pattern.compile("(?ms)^      requestBody:\\n(.*?)(?=^      responses:)").matcher(operation);
      while (bodies.find()) {
        String body = bodies.group(1);
        if (body.matches("(?sm).*^\\s+examples:\\n.*")) continue;
        Matcher reference = Pattern.compile("#/components/schemas/([A-Za-z0-9]+)").matcher(body);
        assertTrue(reference.find(), "request body has neither an inline example nor a schema example: " + operationId);
        String schemaName = reference.group(1);
        assertTrue(yaml.matches("(?sm).*^    " + Pattern.quote(schemaName) + ":\\n      type: object\\n      example:.*"),
            "request body schema has no complete example: " + operationId + " -> " + schemaName);
      }
    }
  }

  private void assertParameterExamples(String yaml) {
    Matcher references = Pattern.compile("(?m)^\\s*- \\$ref: '#/components/parameters/([A-Za-z0-9_-]+)'").matcher(yaml);
    while (references.find()) {
      String name = references.group(1);
      Matcher component = Pattern.compile("(?ms)^    " + Pattern.quote(name)
          + ":\\n(.*?)(?=^    [A-Za-z0-9_-]+:\\n|^  (?:responses|schemas|examples):)").matcher(yaml);
      assertTrue(component.find(), "missing parameter definition: " + name);
      assertHasExample(component.group(1), name);
    }

    Matcher inlineParameters = Pattern.compile("(?ms)^        - name: ([^\\n]+)\\n(.*?)(?=^        - (?:name:|\\$ref:)|^      (?:requestBody|responses):)").matcher(yaml);
    while (inlineParameters.find()) assertHasExample(inlineParameters.group(2), inlineParameters.group(1));

    Matcher pathParameters = Pattern.compile("(?ms)^      - name: ([^\\n]+)\\n(.*?)(?=^      - (?:name:|\\$ref:)|^    (?:get|post|put|delete|patch):)").matcher(yaml);
    while (pathParameters.find()) assertHasExample(pathParameters.group(2), pathParameters.group(1));
  }

  private void assertHasExample(String parameter, String name) {
    assertTrue(Pattern.compile("(?ms)^\\s*example:\\s*\\S.*$|[,\\{]\\s*example:\\s*[^\\s,}]+|^\\s+examples:\\s*\\n\\s+[A-Za-z0-9_-]+:\\s*\\n\\s+(?:summary|value|externalValue):\\s*\\S.*$")
        .matcher(parameter).find(), "missing non-empty parameter example: " + name);
  }
}
