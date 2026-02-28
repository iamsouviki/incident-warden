package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Layer 8 — CIRCUIT BREAKER CHECK
 *
 * Checks the Resilience4J circuit-breaker state for the target system.
 * If the circuit is OPEN (> 50 % error rate in last 60 s), execution is
 * blocked.  If HALF_OPEN, the request is queued for the probe attempt.
 *
 * Circuit naming convention: "mcp-{tool-category}" — e.g. "mcp-database",
 * "mcp-kubernetes", "mcp-cache".
 *
 * Spec reference: §7 Layer 8 — "Track error rates. If a tool failed more
 * than 50% calls in last 60s, its circuit breaks OPEN."
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerGuard implements GuardrailValidator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public GuardrailResult validate(AgentContext context) {
        String circuitName = resolveCircuitName(context);
        CircuitBreaker cb = circuitBreakerRegistry.find(circuitName)
                .orElse(null);

        if (cb == null) {
            // No circuit registered — allow; circuit will be created on first call
            return GuardrailResult.pass(getLayer(), "CIRCUIT_BREAKER_CHECK");
        }

        CircuitBreaker.State state = cb.getState();

        if (state == CircuitBreaker.State.OPEN) {
            log.warn("[GuardrailLayer8] Circuit {} is OPEN — blocking action for incident {}",
                    circuitName, context.getIncident().getId());
            return GuardrailResult.queue(getLayer(), "CIRCUIT_BREAKER_CHECK",
                    "Circuit breaker for '" + circuitName + "' is OPEN (error rate exceeded threshold). " +
                    "Action queued — will retry when circuit transitions to HALF_OPEN.");
        }

        if (state == CircuitBreaker.State.HALF_OPEN) {
            log.info("[GuardrailLayer8] Circuit {} is HALF_OPEN — allowing probe attempt", circuitName);
            // Allow one probe through — Resilience4J will handle the state transition
        }

        return GuardrailResult.pass(getLayer(), "CIRCUIT_BREAKER_CHECK");
    }

    /**
     * Maps the action plan tool to its Resilience4J circuit-breaker name.
     */
    private String resolveCircuitName(AgentContext ctx) {
        if (ctx.getActionPlan() == null) return "mcp-default";

        Object tool = ctx.getActionPlan().get("tool");
        if (tool == null) return "mcp-default";

        String t = tool.toString().toUpperCase();
        if (t.contains("DB") || t.contains("DATABASE") || t.contains("POOL"))
            return "mcp-database";
        if (t.contains("SCALE") || t.contains("RESTART") || t.contains("DEPLOY"))
            return "mcp-kubernetes";
        if (t.contains("CACHE") || t.contains("REDIS") || t.contains("FLUSH"))
            return "mcp-cache";
        if (t.contains("ITSM") || t.contains("SERVICENOW") || t.contains("TICKET"))
            return "mcp-itsm";
        return "mcp-default";
    }

    @Override
    public int getLayer() { return 8; }
}
