package com.company.mcp.config.security;

import com.company.mcp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JwtAuthFilter — runs once per request, validates Bearer token and sets
 * {@link SecurityContextHolder} principal + {@link TenantContext}.
 *
 * Skipped paths (no token required):
 *   /api/auth/**   — login / refresh
 *   /actuator/**   — health probes
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Allow unauthenticated access to login + actuator
        if (path.startsWith("/api/auth/") || path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwtUtil.validateToken(token)) {
                String username = jwtUtil.extractUsername(token);
                String tenantId = jwtUtil.extractTenantId(token);
                String role     = jwtUtil.extractRole(token);

                // Wire tenant context for downstream components
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                }

                // Set Spring Security principal
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + (role != null ? role.toUpperCase() : "USER"))));
                auth.setDetails(request);
                SecurityContextHolder.getContext().setAuthentication(auth);

                log.debug("JWT auth ok: user={} tenant={} role={} path={}", username, tenantId, role, path);
            } else {
                log.debug("JWT rejected for {}", path);
            }
        }

        chain.doFilter(request, response);
    }
}
