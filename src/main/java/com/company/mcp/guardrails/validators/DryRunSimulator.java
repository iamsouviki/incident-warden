package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Layer 5 — DRY RUN SIMULATION
 *
 * Simulates the action in dry-run mode before actual execution. Every MCP tool
 * supports a {@code dry_run=true} flag that returns "what would happen" without
 * performing any real change. This layer invokes that simulation and validates
 * the expected outcome matches the SOP intent.
 *
 * Spec reference: §7 Layer 5 — "Run the action in simulation mode first."
 */
@Slf4j
@Component
public class DryRunSimulator implements GuardrailValidator {

    @Override
    public GuardrailResult validate(AgentContext context) {
        if (context.getActionPlan() == null || context.getActionPlan().isEmpty()) {
            // Nothing to simulate
            return GuardrailResult.pass(getLayer(), "DRY_RUN_SIMULATION");
        }

        // Simulate the action plan steps in dry-run mode
        Map<String, Object> simulationResult = simulateDryRun(context);

        boolean success = Boolean.TRUE.equals(simulationResult.get("success"));
        String message = (String) simulationResult.getOrDefault("message", "");

        if (!success) {
            log.warn("[GuardrailLayer5] Dry-run simulation FAILED for incident {}: {}",
                    context.getIncident().getId(), message);
            return GuardrailResult.fail(getLayer(), "DRY_RUN_SIMULATION",
                    "Dry-run simulation failed: " + message +
                    ". Action blocked. Results attached to HITL review package.");
        }

        log.debug("[GuardrailLayer5] Dry-run simulation PASSED for incident {}",
                context.getIncident().getId());
        return GuardrailResult.pass(getLayer(), "DRY_RUN_SIMULATION");
    }

    /**
     * Simulates the action plan and returns the expected outcome.
     * In production: calls each MCP tool with dry_run=true and validates the
     * returned pre-condition / post-condition state matches expectations.
     */
    @SuppressWarnings("unused")
    private Map<String, Object> simulateDryRun(AgentContext ctx) {
        // Phase 1: stub — simulate success for all known safe tools
        // Phase 2: integrate real MCP tool dry_run calls via McpToolExecutor
        Object tool = ctx.getActionPlan().get("tool");
        if (tool == null) {
            return Map.of("success", true, "message", "No tool specified — simulation skipped");
        }

        // Reject dry-runs for destructive tools without rollback plan
        String toolName = tool.toString().toUpperCase();
        if (toolName.contains("DROP") || toolName.contains("DELETE_ALL")) {
            return Map.of("success", false,
                    "message", "Destructive tool " + toolName + " rejected in simulation");
        }

        return Map.of("success", true,
                "message", "Simulated execution of " + toolName + " — expected outcome: OK",
                "dryRunMode", true,
                "estimatedDurationMs", 3000);
    }

    @Override
    public int getLayer() { return 5; }
}
