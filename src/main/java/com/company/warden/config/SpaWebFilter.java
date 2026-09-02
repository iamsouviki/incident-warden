package com.company.warden.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enterprise Single-Page Application (SPA) Forwarding Filter.
 * Routes client-side browser routes (e.g. /chat, /incidents, /settings/ai)
 * to /index.html when running as a single deployable JAR with embedded UI.
 */
@Component
public class SpaWebFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Pass API requests, actuators, web resources with file extensions, and root through
        if (path.startsWith("/api") ||
            path.startsWith("/actuator") ||
            path.startsWith("/swagger-ui") ||
            path.startsWith("/v3/api-docs") ||
            path.startsWith("/assets") ||
            path.equals("/") ||
            path.equals("/index.html") ||
            path.contains(".")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Forward internal SPA routes to /index.html
        request.getRequestDispatcher("/index.html").forward(request, response);
    }
}
