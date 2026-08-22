package com.company.mcp.config;

import com.company.mcp.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Every API route is authorized by JWT role, per HTTP method.
 *
 * Two deliberate properties:
 *  1. Read and write are separated. A VIEWER can never reach a mutating verb,
 *     including on endpoints added after this file was written — the catch-all
 *     write matcher near the bottom is the fail-closed default.
 *  2. Unauthenticated requests answer 401, not Spring's stateless 403, so the
 *     frontend can distinguish "log in again" from "your role is insufficient".
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtService jwtService;
    private final ObjectMapper json;
    private final List<String> allowedOrigins;

    public SecurityConfig(JwtService jwtService, ObjectMapper json,
                          @Value("${mcp.security.cors.allowed-origins:http://localhost:5173,http://localhost:8080}") String allowedOrigins) {
        this.jwtService = jwtService;
        this.json = json;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(origin -> !origin.isBlank()).toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(c -> c.configurationSource(corsSource())).csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(unauthorizedEntryPoint()).accessDeniedHandler(forbiddenHandler()))
            .authorizeHttpRequests(auth -> auth
                // ── Public: token issuance and liveness only ────────────────────────
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/sso", "/api/auth/refresh").permitAll()
                .requestMatchers("/api/health", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()

                // ── Operator surface: read for everyone signed in ───────────────────
                .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/v1/teams/**", "/api/v1/statuses/**",
                        "/api/v1/incidents/**", "/api/v1/scripts/**", "/api/v1/rag/sops/**",
                        "/api/v1/hitl/**", "/api/v1/telemetry/**", "/api/v1/mcp/servers").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/rag/chat").authenticated()

                // ── Analyst surface: create and triage work ─────────────────────────
                .requestMatchers(HttpMethod.POST, "/api/v1/intake/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/telemetry/events").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/hitl/incidents/*/plan").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/rag/ingest", "/api/v1/rag/upload").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/incidents/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/incidents/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/scripts/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/scripts/**").hasAnyRole("ANALYST", "ADMIN")

                // ── Reviewer surface: an analyst may approve and simulate ───────────
                // Deliberately split from execution below: reviewing a plan and running it
                // against a live system are different levels of authority.
                .requestMatchers(HttpMethod.POST, "/api/v1/hitl/requests/*/decision",
                        "/api/v1/hitl/requests/*/dry-run").hasAnyRole("ANALYST", "ADMIN")

                // ── Admin surface: execution, config, deletion ──────────────────────
                .requestMatchers(HttpMethod.POST, "/api/v1/hitl/requests/*/execute").hasRole("ADMIN")
                .requestMatchers("/api/v1/ai/config/**", "/api/v1/autonomy/**", "/actuator/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/rag/sops/**").hasRole("ADMIN")
                // A team's mail id decides where unattended-action reports land, and its roster
                // decides who is reachable as an assignee, so both are configuration rather than
                // triage data. Without the POST line, adding a member would fall to the
                // ANALYST-or-ADMIN default at the bottom of this list.
                .requestMatchers(HttpMethod.PUT, "/api/v1/teams/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/teams/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/statuses/**", "/api/v1/mcp/servers/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/mcp/rpc").hasAnyRole("ANALYST", "ADMIN")

                // ── Fail-closed default: an unlisted write is never a VIEWER right ───
                .requestMatchers(HttpMethod.POST, "/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/**").hasAnyRole("ANALYST", "ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/**").hasAnyRole("ANALYST", "ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, ex) -> write(response, 401, "Authentication required. Sign in again.");
    }

    private AccessDeniedHandler forbiddenHandler() {
        return (request, response, ex) -> write(response, 403, "Your role is not permitted to perform this action.");
    }

    private void write(jakarta.servlet.http.HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }

    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean public CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration(); cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")); cfg.setAllowedHeaders(List.of("Authorization", "Content-Type")); cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource(); src.registerCorsConfiguration("/**", cfg); return src;
    }
}
