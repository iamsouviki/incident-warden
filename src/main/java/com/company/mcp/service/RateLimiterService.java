package com.company.mcp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window request limiter for the two abuse surfaces this application has:
 * credential guessing on /api/auth/login, and paid or slow LLM calls.
 *
 * ponytail: in-memory counters, so the budget is per instance. Move the deques to
 * Redis (already a dependency) if this is ever load-balanced across replicas.
 */
@Service
public class RateLimiterService {
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final int loginAttemptsPerMinute;
    private final int llmCallsPerMinute;

    public RateLimiterService(@Value("${mcp.security.rate-limit.login-per-minute:10}") int loginAttemptsPerMinute,
                              @Value("${mcp.security.rate-limit.llm-per-minute:20}") int llmCallsPerMinute) {
        this.loginAttemptsPerMinute = loginAttemptsPerMinute;
        this.llmCallsPerMinute = llmCallsPerMinute;
    }

    public boolean allowLogin(String identity) { return allow("login:" + identity, loginAttemptsPerMinute); }

    public boolean allowLlmCall(String identity) { return allow("llm:" + identity, llmCallsPerMinute); }

    /** @return false when {@code key} has already used its budget in the trailing minute. */
    public boolean allow(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) return true;
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        Deque<Instant> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) window.pollFirst();
            if (window.size() >= limitPerMinute) return false;
            window.addLast(Instant.now());
            return true;
        }
    }

    /** Called after a successful login so a legitimate user is not penalised for typos. */
    public void reset(String identity) { hits.remove("login:" + identity); }
}
