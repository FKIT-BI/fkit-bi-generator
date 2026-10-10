package ru.fkit.bi.generator.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Trace-Id";
  public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
  private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String traceId = incoming != null && VALID_TRACE_ID.matcher(incoming).matches()
        ? incoming
        : UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, traceId);
    response.setHeader(HEADER, traceId);
    MDC.put("traceId", traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      response.setHeader(HEADER, traceId);
      MDC.remove("traceId");
    }
  }
}
