package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Layer 4 — BLAST RADIUS GATE
 *
 * Hard-blocks any action that would affect more than the configured blast-radius
 * percentage of production services.  Also blocks during active change freezes.
 *
 * Spec reference: §7 Layer 4 — "Would this action affect more than 40% of
 * production services? Is there an active change freeze window right now?"
 */
@Slf4j
@Component
public class BlastRadiusGate implements GuardrailValidator {

    @Value("${mcp.guardrails.max-blast-radius-pct:40}")
    private double maxBlastRadiusPct;

    @Override
    public GuardrailResult validate(AgentContext context) {
        // ── Change freeze check ───────────────────────────────────────────────
        // In production: query the change_windows table via DB. Here we check
        // a context flag that RiskEvaluatorAgent can set.
        if (Boolean.TRUE.equals(context.getGuardrailsTriggered()) &&
                context.getRiskFactors() != null &&
                context.getRiskFactors().contains("change window")) {
            return GuardrailResult.fail(getLayer(), "BLAST_RADIUS_GATE",
                    "Active change freeze detected. Auto-resolve blocked.");
        }

        // ── Blast-radius check ────────────────────────────────────────────────
        double blastRadius = estimateBlastRadius(context);
        if (blastRadius > maxBlastRadiusPct) {
            log.warn("[GuardrailLayer4] Blast radius {:.1f}% exceeds max {:.1f}% for incident {}",
                    blastRadius, maxBlastRadiusPct, context.getIncident().getId());
            return GuardrailResult.fail(getLayer(), "BLAST_RADIUS_GATE",
                    String.format("Blast radius %.0f%% exceeds maximum %.0f%%. " +
                            "Action cannot be auto-executed. Incident downgraded to HITL.",
                            blastRadius, maxBlastRadiusPct));
        }

        return GuardrailResult.pass(getLayer(), "BLAST_RADIUS_GATE");
    }

    /**
     * Estimate blast radius as a % of production services that could be
     * impacted. In production this would query a service-dependency graph.
     * We approximate using severity and the number of affected systems.
     */
    private double estimateBlastRadius(AgentContext ctx) {
        double base = 0.0;
        String sev = ctx.getIncident().getSeverity();
        if ("P1".equalsIgnoreCase(sev)) base = 30.0;
        else if ("P2".equalsIgnoreCase(sev)) base = 15.0;
        else base = 5.0;

        // Each extra affected system adds ~5%
        String[] affected = ctx.getIncident().getAffectedSystems();
        if (affected != null) base += (affected.length - 1) * 5.0;

        return Math.min(100.0, base);
    }

    @Override
    public int getLayer() { return 4; }
}
