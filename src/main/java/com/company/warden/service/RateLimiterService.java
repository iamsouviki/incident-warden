package com.company.warden.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise distributed sliding-window request limiter for login attempts and LLM calls.
 * Backed by Redis when available for multi-replica deployments with in-memory fallback.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    private static final int MAX_IN_MEMORY_KEYS = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Deque<Instant>> inMemoryHits = new ConcurrentHashMap<>();
    private final int loginAttemptsPerMinute;
    private final int llmCallsPerMinute;

    public RateLimiterService(
            @Value("${mcp.security.rate-limit.login-per-minute:10}") int loginAttemptsPerMinute,
            @Value("${mcp.security.rate-limit.llm-per-minute:20}") int llmCallsPerMinute,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.loginAttemptsPerMinute = loginAttemptsPerMinute;
        this.llmCallsPerMinute = llmCallsPerMinute;
        this.redisTemplate = redisTemplate;
    }

    public boolean allowLogin(String identity) {
        return allow("login:" + identity, loginAttemptsPerMinute);
    }

    public boolean allowLlmCall(String identity) {
        return allow("llm:" + identity, llmCallsPerMinute);
    }

    /**
     * Check if key has remaining quota within the trailing 1-minute window.
     */
    public boolean allow(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) return true;

        if (redisTemplate != null) {
            try {
                String redisKey = RATE_LIMIT_PREFIX + key;
                long now = System.currentTimeMillis();
                long windowStart = now - 60000L;

                // Redis Sorted Set sliding window algorithm
                redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStart);
                Long count = redisTemplate.opsForZSet().zCard(redisKey);

                if (count != null && count >= limitPerMinute) {
                    return false;
                }

                String member = now + ":" + UUID.randomUUID().toString().substring(0, 8);
                redisTemplate.opsForZSet().add(redisKey, member, now);
                redisTemplate.expire(redisKey, Duration.ofSeconds(65));
                return true;
            } catch (Exception e) {
                log.debug("Redis rate limiting unavailable; falling back to in-memory: {}", e.getMessage());
            }
        }

        // In-memory sliding window fallback with auto-pruning
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        if (inMemoryHits.size() >= MAX_IN_MEMORY_KEYS) {
            inMemoryHits.entrySet().removeIf(entry -> {
                Deque<Instant> hits = entry.getValue();
                synchronized (hits) {
                    return hits.isEmpty() || hits.peekLast().isBefore(cutoff);
                }
            });
            if (inMemoryHits.size() >= MAX_IN_MEMORY_KEYS && !inMemoryHits.containsKey(key)) {
                return false;
            }
        }
        Deque<Instant> window = inMemoryHits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(Instant.now());
            return true;
        }
    }

    /**
     * Reset rate limit state for an identity (e.g. on successful login).
     */
    public void reset(String identity) {
        String key = "login:" + identity;
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(RATE_LIMIT_PREFIX + key);
            } catch (Exception ignored) {}
        }
        inMemoryHits.remove(key);
    }
}
