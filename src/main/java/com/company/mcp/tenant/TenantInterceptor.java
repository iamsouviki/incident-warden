package com.company.mcp.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TenantInterceptor — spec §2 "Multi-Tenant Architecture".
 *
 * Extracts the tenant identifier from each incoming HTTP request and stores it
 * in {@link TenantContext} so the current thread has access to it throughout
 * the request lifecycle.
 *
 * Resolution order (first match wins):
 *   1. {@code X-Tenant-Id} HTTP header
 *   2. {@code tenantId} query parameter
 *   3. JWT claim {@code tenant_id} in the {@code Authorization: Bearer …} header
 *      (parsed without full validation here — SecurityConfig handles auth)
 *   4. Falls back to the configured default tenant when none of the above is present.
 *
 * The tenant value is always {@link TenantContext#clear()}d in
 * {@link #afterCompletion} to prevent leaks in thread-pool environments.
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final String HEADER_TENANT_ID  = "X-Tenant-Id";
    private static final String PARAM_TENANT_ID   = "tenantId";
    private static final String DEFAULT_TENANT    = "00000000-0000-0000-0000-000000000001";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String tenantId = resolveTenantId(request);
        TenantContext.set(tenantId);
        log.trace("TenantInterceptor: set tenantId={} for {} {}",
                tenantId, request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // Resolution helpers
    // -------------------------------------------------------------------------

    private String resolveTenantId(HttpServletRequest request) {
        // 1. Explicit header
        String fromHeader = request.getHeader(HEADER_TENANT_ID);
        if (isPresent(fromHeader)) return fromHeader;

        // 2. Query parameter
        String fromParam = request.getParameter(PARAM_TENANT_ID);
        if (isPresent(fromParam)) return fromParam;

        // 3. JWT claim (base64-decoded payload, no signature validation)
        String fromJwt = extractTenantFromJwt(request.getHeader("Authorization"));
        if (isPresent(fromJwt)) return fromJwt;

        // 4. Default
        return DEFAULT_TENANT;
    }

    /**
     * Extracts the {@code tenant_id} claim from a Bearer JWT without
     * validating the signature.  Full validation happens in SecurityConfig.
     */
    private String extractTenantFromJwt(String authHeader) {
        if (!isPresent(authHeader) || !authHeader.startsWith("Bearer ")) return null;
        try {
            String[] parts = authHeader.substring(7).split("\\.");
            if (parts.length < 2) return null;
            // Base64-url decode payload
            String payload = new String(java.util.Base64.getUrlDecoder().decode(
                    padBase64(parts[1])));
            // Quick JSON extraction (avoids a full ObjectMapper dependency here)
            int idx = payload.indexOf("\"tenant_id\"");
            if (idx < 0) return null;
            int colon  = payload.indexOf(':', idx);
            int quote1 = payload.indexOf('"', colon + 1);
            int quote2 = payload.indexOf('"', quote1 + 1);
            if (quote1 < 0 || quote2 < 0) return null;
            String val = payload.substring(quote1 + 1, quote2).trim();
            return val.isEmpty() ? null : val;
        } catch (Exception e) {
            log.debug("TenantInterceptor: could not parse JWT for tenant: {}", e.getMessage());
            return null;
        }
    }

    private static String padBase64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return s + "=".repeat(pad);
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
