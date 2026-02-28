package com.company.mcp.agent;

import com.company.mcp.guardrails.GuardrailResult;
import com.company.mcp.guardrails.GuardrailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Guardrails Agent — Step 7 in the pipeline.
 *
 * Runs the 9-layer safety gate via {@link GuardrailsService}.  Every check
 * must pass before ActionExecutorAgent is allowed to proceed.  If any layer
 * fails the incident is blocked and the decision is overridden to
 * HITL_REQUIRED or ESCALATE_TO_HUMAN depending on severity.
 *
 * Pipeline position: after RiskEvaluatorAgent (priority 5),
 *                    before ActionExecutorAgent (priority 7).
 *
 * Spec reference:
 *   §7 "If even ONE layer fails, the action is blocked — no bypass,
 *       no override, no exceptions."
 */
@Slf4j
@Component
public class GuardrailsAgent extends BaseAgent {

    private final GuardrailsService guardrailsService;

    public GuardrailsAgent(GuardrailsService guardrailsService) {
        super("GuardrailsAgent");
        this.guardrailsService = guardrailsService;
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "GuardrailsAgent: running 9-layer safety gate");

        try {
            GuardrailResult result = guardrailsService.runAll(context);

            if (!result.isPassing()) {
                // Apply the appropriate override to the decision
                String overrideDecision = determineOverrideDecision(context, result);
                context.setDecision(overrideDecision);
                context.setGuardrailsTriggered(true);

                String msg = String.format(
                        "GUARDRAILS_BLOCKED — Layer %d (%s): %s → decision overridden to %s",
                        result.getLayer(), result.getLayerName(),
                        result.getReason(), overrideDecision);

                context.addWarning(msg);
                logWarning(context, msg);

                // For THROTTLE / QUEUE results, mark for retry rather than permanent block
                if (result.getStatus() == GuardrailResult.Status.THROTTLE ||
                        result.getStatus() == GuardrailResult.Status.QUEUE) {
                    logExecution(context, "Action will be retried in the next scheduler cycle");
                }
            } else {
                context.setGuardrailsTriggered(false);
                logExecution(context, "All 9 guardrail layers PASSED — action approved to proceed");
            }

            return context;

        } catch (Exception e) {
            handleException(context, e, "guardrails evaluation");
            // On unexpected error, conservatively escalate
            context.setDecision("ESCALATE_TO_HUMAN");
            return context;
        }
    }

    /**
     * Determines what decision override is appropriate based on which layer
     * failed and the incident severity.
     */
    private String determineOverrideDecision(AgentContext ctx, GuardrailResult result) {
        // Hard rules (Spec §7):
        //   Layer 3 (Prompt Injection) → ESCALATE + security alert
        //   Layer 4 (Blast Radius)     → HITL or ESCALATE
        //   Layer 7 (Loop Detection)   → ESCALATE + disable category
        if (result.getLayer() == 3) return "ESCALATE_TO_HUMAN";
        if (result.getLayer() == 7) return "ESCALATE_TO_HUMAN";
        if (result.getStatus() == GuardrailResult.Status.THROTTLE
                || result.getStatus() == GuardrailResult.Status.QUEUE) return "HITL_REQUIRED";

        // P1 always routes to HITL_REQUIRED (at minimum)
        if ("P1".equalsIgnoreCase(ctx.getIncident().getSeverity())) return "HITL_REQUIRED";

        // Default: HITL for blocked auto-resolutions
        if ("AUTO_RESOLVE".equals(ctx.getDecision())) return "HITL_REQUIRED";

        return "ESCALATE_TO_HUMAN";
    }

    @Override
    public boolean canExecute(AgentContext context) {
        // Only run if there is a decision to gate (skip for already-escalated incidents)
        return context.getDecision() != null
                && !"ESCALATE_TO_HUMAN".equals(context.getDecision());
    }

    @Override
    public int getPriority() {
        return 6; // After RiskEvaluatorAgent (5), before ActionExecutorAgent (7)
    }
}
