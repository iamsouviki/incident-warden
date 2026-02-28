package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;

/**
 * Marker interface for all 9 guardrail layer validators.
 * Implementations are auto-discovered by GuardrailsService.
 */
public interface GuardrailValidator {
    /** Execute this layer's check and return the result. */
    GuardrailResult validate(AgentContext context);
    /** Layer number (1–9). Used for ordering. */
    int getLayer();
}
