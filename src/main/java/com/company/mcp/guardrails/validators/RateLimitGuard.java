package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 6 — RATE LIMIT CHECK
 *
 * Enforces per-tool and per-category sliding-window rate limits to prevent
 * the system from hammering a target service.
 *
 * Rate limits (configurable):
 *   • Per tool:          max 10 calls / minute
 *   • Per service restart: max 5 restarts / 30 minutes
 *   • Per category (auto-resolve): max 20 / hour
 *
 * Implementation: in-memory sliding-window counters
 * (TODO: replace with Redis when spring-data-redis is wired up).
 *
 * Spec reference: §7 Layer 6 — "Redis sliding window counters."
 */
@Slf4j
@Component
public class RateLimitGuard implements GuardrailValidator {

    @Value("${mcp.guardrails.rate-limit.per-tool-per-minute:10}")
    private int perToolPerMinute;

    @Value("${mcp.guardrails.rate-limit.restarts-per-30min:5}")
    private int restartsPerThirtyMin;

    @Value("${mcp.guardrails.rate-limit.auto-resolve-per-hour:20}")
    private int autoResolvePerHour;

    /** key = "tool:YYYYMMDDHHMM" → counter */
    private final Map<String, AtomicInteger> toolWindowCounts = new ConcurrentHashMap<>();
    /** key = "restart:serviceId:window30" → counter */
    private final Map<String, AtomicInteger> restartWindowCounts = new ConcurrentHashMap<>();
    /** key = "auto:category:YYYYMMDDHH" → counter */
    private final Map<String, AtomicInteger> autoResolveWindowCounts = new ConcurrentHashMap<>();

    @Override
    public GuardrailResult validate(AgentContext context) {
        String tool = getToolName(context);
        String tenantId = context.getTenantId();

        // ── Tool call rate ────────────────────────────────────────────────────
        String toolKey = "tool:" + tool + ":" + minuteWindow();
        int toolCount = increment(toolWindowCounts, toolKey);
        if (toolCount > perToolPerMinute) {
            log.warn("[GuardrailLayer6] Rate limit hit: tool={} count={} limit={}/min",
                    tool, toolCount, perToolPerMinute);
            return GuardrailResult.throttle(getLayer(), "RATE_LIMIT_CHECK",
                    "Tool '" + tool + "' has been called " + toolCount + " times in the last minute " +
                    "(limit: " + perToolPerMinute + "). Request throttled — retry next cycle.");
        }

        // ── Service restart rate ──────────────────────────────────────────────
        if (tool.contains("RESTART")) {
            String svcKey = "restart:" + tenantId + ":" + window30MinKey();
            int restartCount = increment(restartWindowCounts, svcKey);
            if (restartCount > restartsPerThirtyMin) {
                log.warn("[GuardrailLayer6] Restart rate limit hit: count={} limit={}/30min",
                        restartCount, restartsPerThirtyMin);
                return GuardrailResult.throttle(getLayer(), "RATE_LIMIT_CHECK",
                        "Service restart rate limit exceeded: " + restartCount + " restarts in 30 minutes " +
                        "(limit: " + restartsPerThirtyMin + "). Queued for next window.");
            }
        }

        // ── Auto-resolve per category ─────────────────────────────────────────
        if ("AUTO_RESOLVE".equals(context.getDecision())) {
            String cat = context.getClassifiedCategory() != null ? context.getClassifiedCategory() : "UNKNOWN";
            String autoKey = "auto:" + tenantId + ":" + cat + ":" + hourWindow();
            int autoCount = increment(autoResolveWindowCounts, autoKey);
            if (autoCount > autoResolvePerHour) {
                log.warn("[GuardrailLayer6] Auto-resolve rate limit hit: category={} count={} limit={}/hr",
                        cat, autoCount, autoResolvePerHour);
                return GuardrailResult.throttle(getLayer(), "RATE_LIMIT_CHECK",
                        "Auto-resolve rate limit for category '" + cat + "' exceeded: " +
                        autoCount + " in the last hour (limit: " + autoResolvePerHour + "). Queued.");
            }
        }

        return GuardrailResult.pass(getLayer(), "RATE_LIMIT_CHECK");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int increment(Map<String, AtomicInteger> map, String key) {
        return map.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private String getToolName(AgentContext ctx) {
        if (ctx.getActionPlan() != null && ctx.getActionPlan().get("tool") != null)
            return ctx.getActionPlan().get("tool").toString().toUpperCase();
        return "UNKNOWN";
    }

    /** "YYYYMMDDHHMM" window key */
    private String minuteWindow() {
        long epoch = Instant.now().toEpochMilli();
        return String.valueOf(epoch / 60_000); // changes every minute
    }

    /** "YYYYMMDDHH_30m" window key (changes every 30 min) */
    private String window30MinKey() {
        long epoch = Instant.now().toEpochMilli();
        return String.valueOf(epoch / (30 * 60_000));
    }

    /** "YYYYMMDDHH" hourly window key */
    private String hourWindow() {
        long epoch = Instant.now().toEpochMilli();
        return String.valueOf(epoch / 3_600_000);
    }

    @Override
    public int getLayer() { return 6; }
}
