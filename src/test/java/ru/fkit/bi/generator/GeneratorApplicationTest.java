package ru.fkit.bi.generator;

import static org.hamcrest.Matchers.matchesPattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(GeneratorApplicationTest.TestEndpoints.class)
@WithMockUser
@ExtendWith(OutputCaptureExtension.class)
class GeneratorApplicationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withInitScript("schema-release-ready.sql");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("fkit.auth.jwt-secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
  }

  @Autowired MockMvc mockMvc;
  @Autowired ApplicationContext applicationContext;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void successPreservesValidTraceIdAndReturnsItInHeader() throws Exception {
    mockMvc.perform(get("/__test/success").header("X-Trace-Id", "client.trace-42"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "client.trace-42"));
  }

  @Test
  void structuredLogsContainSafeTraceIdAndNoAuthorizationSecret(CapturedOutput output) throws Exception {
    String bearerToken = signedTestToken();
    mockMvc.perform(get("/__test/log-probe")
            .header("X-Trace-Id", "safe-log-trace-42")
            .header("Authorization", "Bearer " + bearerToken))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "safe-log-trace-42"));

    String logLine = output.getOut().lines()
        .filter(line -> line.contains("trace-log-probe"))
        .findFirst().orElseThrow();
    new ObjectMapper().readTree(logLine);
    assertThat(logLine).contains("safe-log-trace-42")
        .doesNotContain(bearerToken);
  }

  private String signedTestToken() {
    String secret = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    Instant now = Instant.now();
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(List.of("fkit-bi-api"))
            .subject(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
  }

  @Test
  void missingOrInvalidTraceIdIsReplacedWithUuid() throws Exception {
    mockMvc.perform(get("/__test/success").header("X-Trace-Id", "bad trace value"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void missingTraceIdIsGenerated() throws Exception {
    mockMvc.perform(get("/__test/success"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void oversizedTraceIdIsReplaced() throws Exception {
    mockMvc.perform(get("/__test/success").header("X-Trace-Id", "x".repeat(129)))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void maximumAllowedTraceIdIsPreserved() throws Exception {
    String traceId = "a".repeat(128);
    mockMvc.perform(get("/__test/success").header("X-Trace-Id", traceId))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", traceId));
  }

  @Test
  void malformedJsonUsesCommonErrorEnvelope() throws Exception {
    mockMvc.perform(post("/__test/validate").header("X-Trace-Id", "malformed-7")
            .contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Trace-Id", "malformed-7"))
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.traceId").value("malformed-7"))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.details").isMap());
  }

  @Test
  void beanValidationUses422AndDoesNotEchoSubmittedValue() throws Exception {
    var result = mockMvc.perform(post("/__test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("value"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value("Value is invalid"))
        .andReturn();
    String generatedTraceId = result.getResponse().getHeader("X-Trace-Id");
    assertNotNull(generatedTraceId);
    assertEquals(generatedTraceId, com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.traceId"));
  }

  @Test
  void multipleInvalidFieldsAreReportedWithoutEchoingValues() throws Exception {
    var result = mockMvc.perform(post("/__test/validate-multiple").contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"private-secret\",\"quantity\":-999}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors[*].path").value(org.hamcrest.Matchers.containsInAnyOrder("password", "quantity")))
        .andReturn();
    String body = result.getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("private-secret"));
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("-999"));
  }

  @Test
  void missingRouteAndUnsupportedMethodKeepFrameworkStatusesInCommonEnvelope() throws Exception {
    mockMvc.perform(get("/__test/missing").header("X-Trace-Id", "missing-4"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.traceId").value("missing-4"))
        .andExpect(jsonPath("$.fieldErrors").isArray());
    mockMvc.perform(post("/__test/success").header("X-Trace-Id", "method-4"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.traceId").value("method-4"));
  }

  @Test
  void requestParameterValidationUses422AndSafePath() throws Exception {
    mockMvc.perform(get("/__test/parameter-validation").param("count", "0"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("count"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value("Value is invalid"));
  }

  @Test
  void unexpectedFailureIsSanitizedAndCorrelated() throws Exception {
    mockMvc.perform(get("/__test/fail").header("X-Trace-Id", "failure-9"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string("X-Trace-Id", "failure-9"))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.traceId").value("failure-9"))
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
        .andExpect(jsonPath("$.stackTrace").doesNotExist())
        .andExpect(jsonPath("$.details.password").doesNotExist());
  }

  @Test
  void actuatorLivenessAndReadinessAreAvailable() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
        .andExpect(jsonPath("$.components").doesNotExist());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.schemaRelease.details.release").value("002-authentication"));
  }

  @Test
  @WithAnonymousUser
  void prometheusMetricsAreAvailableToInternalScrapersWithoutChangingReadiness() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
        .andExpect(content().string(containsString("application=\"fkit-bi-generator\"")));
    mockMvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void readinessRequiresTheExactSchemaReleaseAndLivenessStaysUp() throws Exception {
    jdbcTemplate.update("UPDATE equipment.schema_release SET release = '002-next' WHERE singleton = TRUE");
    mockMvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.components.schemaRelease.details.reason").value("RELEASE_MISMATCH"));
    mockMvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

    jdbcTemplate.update("UPDATE equipment.schema_release SET release = '002-authentication' WHERE singleton = TRUE");
    jdbcTemplate.update("DELETE FROM equipment.schema_release WHERE singleton = TRUE");
    mockMvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.components.schemaRelease.details.reason").value("RELEASE_MISSING"));
    jdbcTemplate.update("INSERT INTO equipment.schema_release (singleton, release) VALUES (TRUE, '002-authentication')");
  }

  @Test
  void readinessFailsWhileApplicationRefusesTraffic() throws Exception {
    AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
    try {
      mockMvc.perform(get("/actuator/health/liveness"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
      mockMvc.perform(get("/actuator/health/readiness"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    } finally {
      AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  @Test
  @WithAnonymousUser
  void sharedJwtProtectsGeneratorApiAndActuatorProbesRemainPublic() throws Exception {
    mockMvc.perform(get("/__test/success")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    String secret = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    var key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    Instant now = Instant.now();
    String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(java.util.List.of("fkit-bi-api"))
            .subject(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
    mockMvc.perform(get("/__test/success").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    String wrongAudience = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(java.util.List.of("wrong"))
            .subject(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
    mockMvc.perform(get("/__test/success").header("Authorization", "Bearer " + wrongAudience))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    String wrongIssuer = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("other-issuer").audience(java.util.List.of("fkit-bi-api"))
            .subject(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
    String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(java.util.List.of("fkit-bi-api"))
            .subject(UUID.randomUUID().toString()).issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(1)).build())).getTokenValue();
    String futureIssued = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(java.util.List.of("fkit-bi-api"))
            .subject(UUID.randomUUID().toString()).issuedAt(now.plusSeconds(60)).expiresAt(now.plusSeconds(1800)).build())).getTokenValue();
    String longLived = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(java.util.List.of("fkit-bi-api"))
            .subject(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(1801)).build())).getTokenValue();
    for (String invalidToken : new String[] {"not.a.jwt", wrongIssuer, expired, futureIssued, longLived}) {
      mockMvc.perform(get("/__test/success").header("Authorization", "Bearer " + invalidToken))
          .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @TestConfiguration
  static class TestEndpoints {
    @Bean TestApi testApi() { return new TestApi(); }
  }

  @RestController
  @RequestMapping("/__test")
  static class TestApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestApi.class);
    @GetMapping("/success") String success() { return "ok"; }
    @GetMapping("/log-probe") String logProbe() { LOGGER.info("trace-log-probe"); return "ok"; }
    @GetMapping("/fail") String fail() { throw new IllegalStateException("password=never-return-this"); }
    @PostMapping("/validate") String validate(@Valid @RequestBody TestRequest request) { return request.value(); }
    @PostMapping("/validate-multiple") String validateMultiple(@Valid @RequestBody MultiInvalidRequest request) { return "ok"; }
    @GetMapping("/parameter-validation") String parameterValidation(@RequestParam("count") @Min(1) int count) { return "ok"; }
  }

  record TestRequest(@NotBlank String value) {}
  record MultiInvalidRequest(@Size(min = 30) String password, @Min(1) int quantity) {}
}
