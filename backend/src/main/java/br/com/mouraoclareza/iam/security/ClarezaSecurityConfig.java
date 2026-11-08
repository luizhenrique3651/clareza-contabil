package br.com.mouraoclareza.iam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Configuração Spring Security 6 stateless com filtro multi-tenant e respostas RFC 9457 (401/403).
 */
@Configuration
@EnableWebSecurity
public class ClarezaSecurityConfig {

    private final ClarezaAuthenticationFilter clarezaAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public ClarezaSecurityConfig(
            ClarezaAuthenticationFilter clarezaAuthenticationFilter,
            ObjectMapper objectMapper
    ) {
        this.clarezaAuthenticationFilter = clarezaAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("type", "https://clareza.example/problems/authentication-required");
                            body.put("title", "Unauthorized");
                            body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
                            body.put("detail", "Autenticação obrigatória para acessar este recurso.");
                            body.put("instance", request.getRequestURI());
                            body.put("code", "AUTHENTICATION_REQUIRED");
                            body.put("requestId", UUID.randomUUID().toString());

                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            objectMapper.writeValue(response.getWriter(), body);
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(clarezaAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
