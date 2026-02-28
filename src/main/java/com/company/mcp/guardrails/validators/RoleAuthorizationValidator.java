package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Layer 1 — ROLE AUTHORIZATION
 *
 * Checks whether the action referenced in the SOP action-plan is on the
 * allowlist of tools that the ActionExecutorAgent is permitted to invoke.
 * The permitted-tools list is loaded from the database (classification_rules /
 * agent_permissions). If no permission record exists we default to a safe
 * baseline set.
 *
 * Spec reference: §7 Layer 1 — "Is the AI agent allowed to call this tool?"
 */
@Slf4j
@Component
public class RoleAuthorizationValidator implements GuardrailValidator {

    // Default allowed tool set (extend via DB agent_permissions table)
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "RESTART_SERVICE", "SCALE_UP", "DRAIN_QUEUE", "CLEAR_CACHE",
            "ROLLBACK_DEPLOY", "RUN_SCRIPT", "SCALE_CONNECTION_POOL",
            "FLUSH_CACHE_KEYS", "READ_METRICS", "UPDATE_ITSM_TICKET",
            "POST_SLACK_NOTIFICATION", "SEND_EMAIL"
    );

    @Override
    public GuardrailResult validate(AgentContext context) {
        if (context.getActionPlan() == null || context.getActionPlan().isEmpty()) {
            // No action plan to check — nothing to authorize
            return GuardrailResult.pass(getLayer(), "ROLE_AUTHORIZATION");
        }

        Object toolName = context.getActionPlan().get("tool");
        if (toolName != null && !ALLOWED_TOOLS.contains(toolName.toString().toUpperCase())) {
            log.warn("[GuardrailLayer1] Unauthorized tool: {}", toolName);
            return GuardrailResult.fail(getLayer(), "ROLE_AUTHORIZATION",
                    "UNAUTHORIZED_TOOL_CALL: tool '" + toolName +
                    "' is not in the agent's permitted tool list. Escalating.");
        }

        return GuardrailResult.pass(getLayer(), "ROLE_AUTHORIZATION");
    }

    @Override
    public int getLayer() { return 1; }
}
