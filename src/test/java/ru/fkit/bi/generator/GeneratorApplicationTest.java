package ru.fkit.bi.generator;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:testdb")
@AutoConfigureMockMvc
@Import(GeneratorApplicationTest.TestEndpoints.class)
class GeneratorApplicationTest {
  @Autowired MockMvc mockMvc;
  @Autowired ApplicationContext applicationContext;

  @Test
  void successPreservesValidTraceIdAndReturnsItInHeader() throws Exception {
    mockMvc.perform(get("/__test/success").header("X-Trace-Id", "client.trace-42"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "client.trace-42"));
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
    mockMvc.perform(post("/__test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("value"))
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
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void readinessFailsWhileApplicationRefusesTraffic() throws Exception {
    AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
    try {
      mockMvc.perform(get("/actuator/health/readiness"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    } finally {
      AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  @TestConfiguration
  static class TestEndpoints {
    @Bean TestApi testApi() { return new TestApi(); }
  }

  @RestController
  @RequestMapping("/__test")
  static class TestApi {
    @GetMapping("/success") String success() { return "ok"; }
    @GetMapping("/fail") String fail() { throw new IllegalStateException("password=never-return-this"); }
    @PostMapping("/validate") String validate(@Valid @RequestBody TestRequest request) { return request.value(); }
  }

  record TestRequest(@NotBlank String value) {}
}
