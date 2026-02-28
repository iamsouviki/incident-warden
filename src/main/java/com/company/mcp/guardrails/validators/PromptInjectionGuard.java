package com.company.mcp.guardrails.validators;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.guardrails.GuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Layer 3 — PROMPT INJECTION GUARD
 *
 * Scans the incident title and description for common LLM-prompt-injection
 * patterns, base-64 encoded payloads, and script fragments that could hijack
 * the AI agents' behaviour.
 *
 * Spec reference: §7 Layer 3 — "Patterns like 'ignore previous instructions',
 * base64 encoded commands, or script injection that could hijack the AI."
 */
@Slf4j
@Component
public class PromptInjectionGuard implements GuardrailValidator {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Classic ignore/override instructions
            Pattern.compile("ignore (all |previous |prior )?(instructions?|prompts?|rules?)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("you are now|act as|pretend (to be|you are)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard (your |the |all |previous )?instructions?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("system prompt|<\\|im_start\\||\\[INST\\]",
                    Pattern.CASE_INSENSITIVE),
            // Shell / script injection fragments
            Pattern.compile("[;&`|$(){}]\\s*\\w+",    // shell metacharacters
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("<script|javascript:|data:text/html",
                    Pattern.CASE_INSENSITIVE),
            // SQL injection patterns in incident text
            Pattern.compile("('|\")(\\s*)(or|and)(\\s*)('|\")?[0-9]+=",
                    Pattern.CASE_INSENSITIVE)
    );

    @Override
    public GuardrailResult validate(AgentContext context) {
        String combined = buildCombinedText(context);
        if (combined.isBlank()) {
            return GuardrailResult.pass(getLayer(), "PROMPT_INJECTION_GUARD");
        }

        // Check for obvious injection patterns
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(combined).find()) {
                log.warn("[GuardrailLayer3] Prompt injection detected in incident {}: pattern={}",
                        context.getIncident().getId(), p.pattern());
                return GuardrailResult.fail(getLayer(), "PROMPT_INJECTION_GUARD",
                        "Potential prompt injection detected. Incident quarantined. " +
                        "Security team notified. Pattern: " + p.pattern());
            }
        }

        // Check for base64-encoded payloads
        if (containsBase64Payload(combined)) {
            log.warn("[GuardrailLayer3] Base64 payload detected in incident {}",
                    context.getIncident().getId());
            return GuardrailResult.fail(getLayer(), "PROMPT_INJECTION_GUARD",
                    "Base64-encoded payload detected in incident text. Quarantined.");
        }

        return GuardrailResult.pass(getLayer(), "PROMPT_INJECTION_GUARD");
    }

    private String buildCombinedText(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getIncident().getTitle() != null)
            sb.append(ctx.getIncident().getTitle()).append(" ");
        if (ctx.getIncident().getDescription() != null)
            sb.append(ctx.getIncident().getDescription());
        return sb.toString();
    }

    private boolean containsBase64Payload(String text) {
        // Look for long base64-like strings (>= 40 chars of base64 chars)
        Pattern b64 = Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}");
        var matcher = b64.matcher(text);
        while (matcher.find()) {
            try {
                String decoded = new String(Base64.getDecoder().decode(matcher.group()));
                // If decoded string contains printable ASCII commands, flag it
                if (decoded.matches(".*[;&|`$<>].*") || decoded.contains("ignore")) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Not valid base64 — skip
            }
        }
        return false;
    }

    @Override
    public int getLayer() { return 3; }
}
