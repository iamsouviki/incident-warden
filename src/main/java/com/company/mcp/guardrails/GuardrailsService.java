package com.company.mcp.guardrails;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.validators.GuardrailValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * GuardrailsService — chain-of-responsibility runner for all 9 safety layers.
 *
 * All validators annotated with {@code @Component} that implement
 * {@link GuardrailValidator} are auto-injected by Spring.  They are run in
 * ascending layer order (1 → 9).  If any layer returns a non-PASS result, the
 * chain stops and the result is returned immediately (fail-fast).
 *
 * Spec reference: §7 "If even ONE layer fails, the action is blocked — no
 * bypass, no override, no exceptions."
 */
@Slf4j
@Service
public class GuardrailsService {

    private final List<GuardrailValidator> validators;

    public GuardrailsService(List<GuardrailValidator> validators) {
        this.validators = validators.stream()
                .sorted(Comparator.comparingInt(GuardrailValidator::getLayer))
                .collect(Collectors.toList());
        log.info("GuardrailsService initialised with {} validators: {}",
                validators.size(),
                validators.stream()
                        .map(v -> "Layer" + v.getLayer())
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Run all 9 layers in order against the provided context.
     *
     * @return the first failing {@link GuardrailResult}, or a synthetic PASS
     *         result if all layers passed.
     */
    public GuardrailResult runAll(AgentContext context) {
        log.debug("Running guardrails for incident {}", context.getIncident().getId());

        for (GuardrailValidator v : validators) {
            GuardrailResult result = v.validate(context);
            logLayerResult(context, result);
            if (!result.isPassing()) {
                log.warn("Guardrail BLOCKED at Layer {}-{} for incident {}: {}",
                        result.getLayer(), result.getLayerName(),
                        context.getIncident().getId(), result.getReason());
                return result;
            }
        }

        log.debug("All {} guardrail layers PASSED for incident {}",
                validators.size(), context.getIncident().getId());
        return GuardrailResult.pass(0, "ALL_LAYERS_PASSED");
    }

    private void logLayerResult(AgentContext ctx, GuardrailResult r) {
        if (r.isPassing()) {
            log.debug("[Guardrail] Layer {}-{} PASS  — incident {}",
                    r.getLayer(), r.getLayerName(), ctx.getIncident().getId());
        } else {
            log.warn("[Guardrail] Layer {}-{} {}   — incident {} — {}",
                    r.getLayer(), r.getLayerName(), r.getStatus(),
                    ctx.getIncident().getId(), r.getReason());
        }
    }
}
