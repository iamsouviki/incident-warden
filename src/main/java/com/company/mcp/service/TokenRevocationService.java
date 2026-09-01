package com.company.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise token revocation and session denylist service.
 * Supports Redis-backed distributed denylist with seamless in-memory fallback.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);
    private static final String TOKEN_DENYLIST_PREFIX = "auth:revoked:token:";
    private static final String USER_REVOCATION_PREFIX = "auth:revoked:user:";

    private final StringRedisTemplate redisTemplate;

    // In-memory fallback stores when Redis is disabled or unavailable
    private final Map<String, Instant> inMemoryRevokedTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> inMemoryUserRevocations = new ConcurrentHashMap<>();

    public TokenRevocationService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Revoke a specific token by its JWT ID (jti) until its expiration time.
     */
    public void revokeToken(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Duration ttl = (expiresAt != null && expiresAt.isAfter(now))
                ? Duration.between(now, expiresAt)
                : Duration.ofHours(3);

        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(TOKEN_DENYLIST_PREFIX + jti, now.toString(), ttl);
                return;
            } catch (Exception e) {
                log.warn("Redis unavailable for token revocation; falling back to in-memory: {}", e.getMessage());
            }
        }

        inMemoryRevokedTokens.put(jti, expiresAt != null ? expiresAt : now.plus(ttl));
    }

    /**
     * Invalidate all tokens for a user issued prior to now (e.g. after password reset or account lock).
     */
    public void revokeAllUserTokens(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        if (redisTemplate != null) {
            try {
                // Keep the user revocation marker active for 24 hours
                redisTemplate.opsForValue().set(USER_REVOCATION_PREFIX + username.trim().toLowerCase(),
                        String.valueOf(now.toEpochMilli()), Duration.ofHours(24));
                return;
            } catch (Exception e) {
                log.warn("Redis unavailable for user revocation; falling back to in-memory: {}", e.getMessage());
            }
        }

        inMemoryUserRevocations.put(username.trim().toLowerCase(), now);
    }

    /**
     * Check if a token has been revoked by its jti or if all user tokens were invalidated after its issuance.
     */
    public boolean isRevoked(String jti, String username, Instant issuedAt) {
        Instant now = Instant.now();

        // 1. Check specific jti revocation
        if (jti != null && !jti.isBlank()) {
            if (redisTemplate != null) {
                try {
                    Boolean hasKey = redisTemplate.hasKey(TOKEN_DENYLIST_PREFIX + jti);
                    if (Boolean.TRUE.equals(hasKey)) {
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("Redis lookup failed, checking in-memory: {}", e.getMessage());
                }
            }

            Instant expiry = inMemoryRevokedTokens.get(jti);
            if (expiry != null) {
                if (expiry.isAfter(now)) {
                    return true;
                } else {
                    inMemoryRevokedTokens.remove(jti);
                }
            }
        }

        // 2. Check user-level bulk revocation
        if (username != null && !username.isBlank() && issuedAt != null) {
            String userKey = username.trim().toLowerCase();
            if (redisTemplate != null) {
                try {
                    String cutoffStr = redisTemplate.opsForValue().get(USER_REVOCATION_PREFIX + userKey);
                    if (cutoffStr != null) {
                        long cutoffMillis = Long.parseLong(cutoffStr);
                        if (issuedAt.toEpochMilli() <= cutoffMillis) {
                            return true;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Redis lookup failed for user revocation, checking in-memory: {}", e.getMessage());
                }
            }

            Instant cutoff = inMemoryUserRevocations.get(userKey);
            if (cutoff != null && !issuedAt.isAfter(cutoff)) {
                return true;
            }
        }

        return false;
    }
}
