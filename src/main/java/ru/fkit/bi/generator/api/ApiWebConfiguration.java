package ru.fkit.bi.generator.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiWebConfiguration {
  @Bean
  FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
    FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new TraceIdFilter());
    registration.setOrder(Integer.MIN_VALUE);
    return registration;
  }
}
