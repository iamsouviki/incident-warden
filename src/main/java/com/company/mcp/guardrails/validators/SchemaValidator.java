package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Layer 2 — SCHEMA VALIDATION
 *
 * Verifies that the IncidentRecord inside the context contains all required
 * fields with valid values. Corresponds to Spring Bean Validation
 * ({@code @NotNull, @NotBlank, @Size}) being applied to the Incident model.
 *
 * Spec reference: §7 Layer 2 — "Does the incident record have all required
 * information? No null values, no empty required fields."
 */
@Slf4j
@Component
public class SchemaValidator implements GuardrailValidator {

    @Override
    public GuardrailResult validate(AgentContext context) {
        var incident = context.getIncident();

        if (incident == null) {
            return GuardrailResult.fail(getLayer(), "SCHEMA_VALIDATION",
                    "Incident object is null — cannot process.");
        }

        StringBuilder violations = new StringBuilder();

        if (incident.getId() == null)
            violations.append("id is null; ");
        if (isBlank(incident.getTitle()))
            violations.append("title is blank; ");
        if (isBlank(incident.getSeverity()))
            violations.append("severity is blank; ");
        if (isBlank(incident.getSourceSystem()))
            violations.append("sourceSystem is blank; ");
        if (isBlank(incident.getSourceTicketId()))
            violations.append("sourceTicketId is blank; ");
        if (incident.getTenantId() == null)
            violations.append("tenantId is null; ");

        if (!violations.isEmpty()) {
            log.warn("[GuardrailLayer2] Schema violations: {}", violations);
            return GuardrailResult.fail(getLayer(), "SCHEMA_VALIDATION",
                    "Incomplete incident data — missing required fields: " + violations);
        }

        return GuardrailResult.pass(getLayer(), "SCHEMA_VALIDATION");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public int getLayer() { return 2; }
}
