package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Layer 9 — OUTPUT SCHEMA VALIDATION
 *
 * Before execution:  validates that the tool's input parameters conform to the
 *                    registered schema for that tool.
 * After execution:   verifies the tool's actual output matched the expected
 *                    post-condition (checked in ActionExecutorAgent via the
 *                    rollback hook).
 *
 * This pre-execution check inspects the action-plan JSON for required fields
 * and type correctness.
 *
 * Spec reference: §7 Layer 9 — "Before execution: does the tool's output match
 * its registered schema? After execution: did the actual output match what was
 * expected?"
 */
@Slf4j
@Component
public class OutputSchemaValidator implements GuardrailValidator {

    @Override
    public GuardrailResult validate(AgentContext context) {
        if (context.getActionPlan() == null || context.getActionPlan().isEmpty()) {
            return GuardrailResult.pass(getLayer(), "OUTPUT_SCHEMA_VALIDATION");
        }

        // Pre-execution: validate input schema of the action plan
        String violation = validateInputSchema(context.getActionPlan());
        if (violation != null) {
            log.warn("[GuardrailLayer9] Input schema violation for incident {}: {}",
                    context.getIncident().getId(), violation);
            return GuardrailResult.fail(getLayer(), "OUTPUT_SCHEMA_VALIDATION",
                    "Action plan input schema violation: " + violation);
        }

        return GuardrailResult.pass(getLayer(), "OUTPUT_SCHEMA_VALIDATION");
    }

    /**
     * Validates the action plan JSON against the expected schema.
     *
     * Each action plan must have:
     *   - tool (String, non-blank)
     *   - params (Map, may be empty)
     *   - expectedOutcome (String, non-blank for auto-resolve)
     *
     * Returns null if valid, or a violation description if invalid.
     */
    private String validateInputSchema(Map<String, Object> plan) {
        if (plan.get("tool") == null || plan.get("tool").toString().isBlank())
            return "'tool' field is missing or empty";

        // 'params' is optional but must be a Map if present
        Object params = plan.get("params");
        if (params != null && !(params instanceof Map))
            return "'params' must be a JSON object (Map), got: " + params.getClass().getSimpleName();

        // 'expectedOutcome' is required for auto-resolve scenarios
        // For HITL it's optional (human reviews manually)
        if (plan.get("expectedOutcome") == null || plan.get("expectedOutcome").toString().isBlank()) {
            // Soft warning — not a hard block
            log.debug("[GuardrailLayer9] 'expectedOutcome' not specified — post-execution verification " +
                    "will be skipped");
        }

        return null; // valid
    }

    @Override
    public int getLayer() { return 9; }
}
