package ru.fkit.bi.generator.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {
  private final TraceIdFilter filter = new TraceIdFilter();

  @Test
  void acceptsBoundedTraceIdAndMakesItAvailableToLogs() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader(TraceIdFilter.HEADER, "edge:request-42");

    filter.doFilter(request, response, (servletRequest, servletResponse) ->
        assertThat(MDC.get("traceId")).isEqualTo("edge:request-42"));

    assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("edge:request-42");
    assertThat(MDC.get("traceId")).isNull();
  }

  @Test
  void replacesOverlongOrUnsafeClientTraceIds() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader(TraceIdFilter.HEADER, "x".repeat(129));

    filter.doFilter(request, response, (FilterChain) (servletRequest, servletResponse) -> {});

    String responseTraceId = response.getHeader(TraceIdFilter.HEADER);
    assertThat(responseTraceId).matches("[0-9a-f-]{36}");
    assertThat(responseTraceId).isNotEqualTo("x".repeat(129));
  }
}
