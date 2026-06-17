package com.company.mcp.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Signs and validates JWTs.
 * Secret must be ≥32 bytes for HMAC-SHA256.
 * SSO-ready: generateToken() accepts arbitrary custom claims.
 */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${mcp.jwt.secret:mcp-incident-automation-jwt-secret-key-change-in-prod-32ch}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Issue a JWT with the given expiry (ms). */
    public String generate(String subject, Map<String, Object> claims, long expiryMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMs))
                .signWith(key)
                .compact();
    }

    /** Parse & validate; throws JwtException on any problem. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
