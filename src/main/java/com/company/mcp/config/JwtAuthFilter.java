package com.company.mcp.config;

import com.company.mcp.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the Bearer token from Authorization header,
 * validates it via JwtService, and populates the SecurityContext.
 * SSO tokens issued by external providers can be added here later
 * (check "iss" claim and delegate to an OidcTokenValidator).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    /** A role outside this set is treated as a forged claim, not as a new role. */
    private static final java.util.Set<String> ROLES = java.util.Set.of("VIEWER", "ANALYST", "ADMIN");

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                // Only an "access" token authenticates an API call. A refresh token is
                // long-lived and is accepted at /api/auth/refresh alone.
                if (!"access".equals(claims.get("tokenType", String.class))) {
                    chain.doFilter(request, response);
                    return;
                }
                String username = claims.getSubject();
                String role = String.valueOf(claims.getOrDefault("role", "VIEWER")).toUpperCase();
                String tenantId = String.valueOf(claims.get("tenantId"));
                if (username == null || username.isBlank() || tenantId == null || tenantId.isBlank() || "null".equals(tenantId)) {
                    throw new JwtException("JWT is missing required identity claims");
                }
                if (!ROLES.contains(role)) {
                    throw new JwtException("JWT carries an unknown role claim");
                }
                var principal = new AuthenticatedUser(username, tenantId, role);
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid token — let request proceed unauthenticated; 401 from security config
            }
        }
        chain.doFilter(request, response);
    }
}
