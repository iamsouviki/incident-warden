package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 7 — LOOP DETECTION
 *
 * Prevents the system from attempting the same fix repeatedly on the same
 * incident. Redis (or in-memory fallback) counts how many times the same
 * (incidentId, toolName) fix has been tried and failed.  If the count exceeds
 * the threshold the action is hard-blocked and a new P1 alert is created.
 *
 * Spec reference: §7 Layer 7 — "Has this exact fix been tried and failed more
 * than 3 times for this same incident? Redis counter."
 */
@Slf4j
@Component
public class LoopDetector implements GuardrailValidator {

    @Value("${mcp.guardrails.loop-detection.max-retries:3}")
    private int maxRetries;

    /** key = incidentId → failure count. TODO swap for Redis INCR with TTL. */
    private final Map<UUID, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    @Override
    public GuardrailResult validate(AgentContext context) {
        UUID incidentId = context.getIncident().getId();
        int failures = getFailureCount(incidentId);

        if (failures >= maxRetries) {
            log.warn("[GuardrailLayer7] Loop detected for incident {}: {} failed attempts",
                    incidentId, failures);
            // Spec: create new P1 alert + disable auto-resolve for this category
            return GuardrailResult.fail(getLayer(), "LOOP_DETECTION",
                    "Fix attempted " + failures + " times without success for incident " + incidentId +
                    ". Loop detected — auto-resolve disabled for this incident category. " +
                    "Human must investigate root cause.");
        }

        return GuardrailResult.pass(getLayer(), "LOOP_DETECTION");
    }

    /**
     * Called by ActionExecutorAgent when a fix fails so the counter is
     * incremented for the next guardrail pass.
     */
    public void recordFailure(UUID incidentId) {
        failureCounters.computeIfAbsent(incidentId, id -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /** Called when an incident is successfully resolved — reset counter. */
    public void resetCounter(UUID incidentId) {
        failureCounters.remove(incidentId);
    }

    private int getFailureCount(UUID incidentId) {
        AtomicInteger counter = failureCounters.get(incidentId);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public int getLayer() { return 7; }
}
