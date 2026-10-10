package ru.fkit.bi.generator.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.time.Duration;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import ru.fkit.bi.generator.api.ErrorResponse;
import ru.fkit.bi.generator.api.TraceIdFilter;

@Configuration
public class AuthSecurityConfiguration {
  @Bean SecretKey jwtSecretKey(@Value("${fkit.auth.jwt-secret:}") String configured) {
    if (configured.isBlank()) throw new IllegalStateException("FKIT_JWT_SECRET must contain a base64-encoded key");
    final byte[] bytes;
    try { bytes = Base64.getDecoder().decode(configured); }
    catch (IllegalArgumentException exception) { throw new IllegalStateException("FKIT_JWT_SECRET must be valid base64", exception); }
    if (bytes.length < 32) throw new IllegalStateException("FKIT_JWT_SECRET must contain at least 256 bits");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean JwtDecoder jwtDecoder(SecretKey secretKey) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> issuer = new JwtIssuerValidator("fkit-bi");
    OAuth2TokenValidator<Jwt> timestamp = new JwtTimestampValidator(Duration.ZERO);
    OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains("fkit-bi-api")
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
    OAuth2TokenValidator<Jwt> subject = jwt -> {
      try {
        java.util.UUID.fromString(jwt.getSubject());
        if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null) {
          return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required claims are missing", null));
        }
        if (jwt.getIssuedAt().isAfter(java.time.Instant.now())
            || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
            || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(1800))) {
          return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token lifetime", null));
        }
        return OAuth2TokenValidatorResult.success();
      } catch (RuntimeException invalidSubject) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid subject", null));
      }
    };
    decoder.setJwtValidator(jwt -> {
      OAuth2TokenValidatorResult timestampResult = timestamp.validate(jwt);
      if (timestampResult.hasErrors()) return timestampResult;
      OAuth2TokenValidatorResult issuerResult = issuer.validate(jwt);
      if (issuerResult.hasErrors()) return issuerResult;
      OAuth2TokenValidatorResult audienceResult = audience.validate(jwt);
      return audienceResult.hasErrors() ? audienceResult : subject.validate(jwt);
    });
    return decoder;
  }

  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer
            .authenticationEntryPoint(jsonAuthenticationEntryPoint(objectMapper))
            .jwt(Customizer.withDefaults()))
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(jsonAuthenticationEntryPoint(objectMapper))
            .accessDeniedHandler(jsonAccessDeniedHandler(objectMapper)));
    return http.build();
  }

  private AuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper mapper) {
    return (request, response, exception) -> writeError(mapper, request, response, 401,
        "AUTHENTICATION_FAILED", "Authentication failed");
  }

  private AccessDeniedHandler jsonAccessDeniedHandler(ObjectMapper mapper) {
    return (request, response, exception) -> writeError(mapper, request, response, 403,
        "FORBIDDEN", "Access is forbidden");
  }

  private void writeError(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
      int status, String code, String message) throws IOException {
    Object trace = request.getAttribute(TraceIdFilter.ATTRIBUTE);
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message,
        trace instanceof String value ? value : "unknown", List.of(), Map.of()));
  }
}
