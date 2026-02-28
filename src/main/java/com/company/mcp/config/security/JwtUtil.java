package com.company.mcp.config.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/**
 * JWT utility — signs and validates HS256 tokens.
 *
 * Claims embedded:
 *   sub        → username
 *   tenant_id  → tenantId (read by TenantInterceptor)
 *   role       → ADMIN | ANALYST | VIEWER
 *   iat / exp  → issued-at / expiry
 *
 * Secret is configurable via {@code mcp.jwt.secret} (must be ≥ 32 chars).
 * Expiry is configurable via {@code mcp.jwt.expiry-ms} (default 24 h).
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${mcp.jwt.secret:mcp-incident-automation-jwt-secret-key}")
    private String rawSecret;

    @Value("${mcp.jwt.expiry-ms:86400000}")
    private long expiryMs;

    // -------------------------------------------------------------------------

    public String generateToken(String username, String tenantId, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("tenant_id", tenantId)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(signingKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT invalid: {}", e.getMessage());
            return false;
        }
    }

    public String extractUsername(String token) {
        return claims(token).getSubject();
    }

    public String extractTenantId(String token) {
        return claims(token).get("tenant_id", String.class);
    }

    public String extractRole(String token) {
        return claims(token).get("role", String.class);
    }

    // -------------------------------------------------------------------------

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Pads / truncates the raw secret to exactly 32 bytes for HS256. */
    private SecretKey signingKey() {
        byte[] raw = rawSecret.getBytes(StandardCharsets.UTF_8);
        byte[] key = Arrays.copyOf(raw, 32);         // right-pad with 0x00 if shorter
        if (raw.length > 32) System.arraycopy(raw, 0, key, 0, 32); // truncate if longer
        return Keys.hmacShaKeyFor(key);
    }
}
