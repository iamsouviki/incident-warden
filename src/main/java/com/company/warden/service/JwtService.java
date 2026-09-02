package com.company.warden.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Signs and validates JWTs.
 * Secret must be ≥32 bytes for HMAC-SHA256.
 * SSO-ready: generateToken() accepts arbitrary custom claims.
 */
@Service
public class JwtService {

    private final SecretKey key;

    /**
     * No default value: a deployment without MCP_JWT_SECRET must fail to start
     * rather than sign tokens with a key that is published in this repository.
     */
    public JwtService(@Value("${mcp.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("MCP_JWT_SECRET must be set and contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Issue a JWT with the given expiry (ms) and unique jti. */
    public String generate(String subject, Map<String, Object> claims, long expiryMs) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
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

    public boolean isRefreshToken(String token) {
        try {
            return "refresh".equals(parse(token).get("tokenType", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** True only for a token minted as an API access token. */
    public boolean isAccessToken(String token) {
        try {
            return "access".equals(parse(token).get("tokenType", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
