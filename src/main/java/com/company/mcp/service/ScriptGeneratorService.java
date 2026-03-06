package com.company.mcp.service;

import com.company.mcp.model.SopScriptRequest;
import com.company.mcp.service.ScriptGuardrailValidator.GuardrailBlockException;
import com.company.mcp.service.ScriptGuardrailValidator.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * ScriptGeneratorService — generates SOP-scoped remediation scripts via an
 * OpenAI-compatible LLM API, then validates them through 5 guardrail layers
 * before returning the approved script.
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li>Scripts are scoped to a single SOP step — the LLM is given the exact SOP
 *       step text and is forbidden from doing anything beyond it.</li>
 *   <li>The command allowlist for the LLM is determined by the SOP category
 *       (APPLICATION, PERFORMANCE, DATABASE, etc.).</li>
 *   <li>Every generated script passes through {@link ScriptGuardrailValidator}
 *       before being returned. BLOCK-level findings throw
 *       {@link GuardrailBlockException} — callers must handle this.</li>
 *   <li>If the LLM API is unavailable, a keyword-matched built-in template
 *       is returned instead (template-fallback mode).</li>
 * </ul>
 *
 * <h3>HTTP client</h3>
 * Uses {@link java.net.HttpURLConnection} directly — no Spring AI or third-party
 * HTTP client required. Compatible with any OpenAI-compatible endpoint
 * (OpenAI, Ollama, Azure, LM Studio, LocalAI, Groq, Together AI …).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptGeneratorService {

    private final ScriptGuardrailValidator guardrailValidator;
    private final ObjectMapper objectMapper;

    /**
     * Spring AI ChatClient — auto-wired when a provider starter is active
     * (e.g. {@code SPRING_AI_OPENAI_CHAT_ENABLED=true} with an API key, or
     * {@code SPRING_AI_OLLAMA_CHAT_ENABLED=true}).
     * <p>
     * When present, used as the primary LLM call path.
     * When absent, falls back to direct-HTTP mode via {@link #callLlmDirect}.
     */
    @Autowired(required = false)
    private ChatClient chatClient;

    // ── Config ────────────────────────────────────────────────────────────────

    @Value("${mcp.script-gen.api-url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${mcp.script-gen.api-key:}")
    private String apiKey;

    @Value("${mcp.script-gen.model:gpt-4o}")
    private String model;

    @Value("${mcp.script-gen.max-tokens:768}")
    private int maxTokens;

    @Value("${mcp.script-gen.temperature:0.1}")
    private double temperature;

    @Value("${mcp.script-gen.api-timeout-ms:0}")
    private int apiTimeoutMs;

    // ── System prompt ─────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            You are a strict remediation script generator for an automated incident response system.

            ABSOLUTE RULES — violating any rule makes the output invalid:
            1. Implement ONLY the exact steps described in SOP_STEP. Do nothing else.
            2. Use ONLY commands from ALLOWED_COMMANDS. No other commands are permitted.
            3. Do NOT install any packages (no apt, yum, dnf, pip, npm, brew, etc.).
            4. Do NOT modify crontabs, user accounts, firewall rules, or network configuration.
            5. Do NOT open SSH connections inside the script.
            6. Do NOT use eval with external variables or Invoke-Expression with dynamic content.
            7. Do NOT spawn background processes (no nohup, screen, tmux, Start-Job).
            8. Bash scripts MUST start with: #!/bin/bash and include: set -e
            9. PowerShell scripts MUST include: $ErrorActionPreference = "Stop"
            10. Include echo / Write-Host audit statements for every major step.
            11. Keep the script under 80 lines. No markdown code fences. Raw script only.
            12. The MCP header comment block will be prepended externally — do NOT include it.
            """;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a remediation script scoped to the given SOP step.
     *
     * @param request all context needed: SOP step text, category, title, host, OS
     * @return the validated, MCP-header-injected script ready for SSH upload
     * @throws GuardrailBlockException if any guardrail layer blocks the generated script
     * @throws ScriptGenerationException if the LLM API fails AND no template fallback exists
     */
    public String generateFromSopStep(SopScriptRequest request) {
        log.info("[ScriptGen] Generating {} script for SOP '{}' on host '{}'",
                request.getOs(), request.getSopTitle(), request.getTargetHost());

        String rawScript = callLlm(request);
        String withHeader = injectMcpHeader(rawScript, request);

        // Run 5-layer guardrails
        ValidationResult result = guardrailValidator.validate(withHeader, request);

        if (result.isBlocked()) {
            log.error("[ScriptGen] Script BLOCKED by guardrails: {}", result.summary());
            throw new GuardrailBlockException(result.summary());
        }

        if (result.isWarning()) {
            log.warn("[ScriptGen] Script passed with WARNINGS: {}", result.summary());
        } else {
            log.info("[ScriptGen] Script passed all guardrail layers ({})",
                    withHeader.lines().count() + " lines");
        }

        return withHeader;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM HTTP call
    // ─────────────────────────────────────────────────────────────────────────

    private String callLlm(SopScriptRequest request) {
        // ── Path 1: Spring AI ChatClient (preferred — provider-agnostic) ──────
        if (chatClient != null) {
            try {
                log.info("[ScriptGen] Using Spring AI ChatClient for script generation");
                String userMessage = buildUserMessage(request);
                String content = chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userMessage)
                        .call()
                        .content();
                if (content == null || content.isBlank()) {
                    throw new ScriptGenerationException("Spring AI ChatClient returned empty response");
                }
                // Strip markdown fences if LLM added them despite instructions
                return content
                        .replaceAll("(?im)^```[a-z]*\\s*$", "")
                        .replaceAll("(?im)^```\\s*$", "")
                        .trim();
            } catch (ScriptGenerationException e) {
                throw e;
            } catch (Exception e) {
                log.error("[ScriptGen] Spring AI ChatClient failed: {}", e.getMessage());
                log.warn("[ScriptGen] Falling back to direct-HTTP or template");
            }
        }

        // ── Path 2: Direct HTTP (OpenAI-compatible endpoint) ─────────────────
        return callLlmDirect(request);
    }

    /**
     * Direct HTTP call to any OpenAI-compatible endpoint.
     * Used when Spring AI ChatClient is not configured,
     * or as a fallback if the ChatClient call fails.
     */
    private String callLlmDirect(SopScriptRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[ScriptGen] No API key configured — using template fallback");
            return getTemplateFallback(request);
        }

        try {
            String requestBody = buildRequestBody(request);
            byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            // 0 means "no timeout" for HttpURLConnection; use it by default for slow models.
            int timeoutMs = Math.max(0, apiTimeoutMs);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                String errBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[ScriptGen] LLM API returned HTTP {}: {}", status, errBody);
                log.warn("[ScriptGen] Falling back to template");
                return getTemplateFallback(request);
            }

            String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return extractContent(responseBody);

        } catch (IOException e) {
            log.error("[ScriptGen] LLM API call failed: {}", e.getMessage());
            log.warn("[ScriptGen] Falling back to template");
            return getTemplateFallback(request);
        }
    }

    /** Builds the prompt user-message string (used by both Spring AI and direct-HTTP paths). */
    private String buildUserMessage(SopScriptRequest request) {
        String shell = request.isWindows() ? "PowerShell" : "Bash";
        String allowedCommands = request.getAllowedCommands() != null
                ? String.join(", ", request.getAllowedCommands())
                : "(use standard OS utilities only)";
        return """
                Generate a %s script for the following SOP step.

                SOP_STEP: %s
                SOP_CATEGORY: %s
                SOP_TITLE: %s
                TARGET_HOST: %s
                ALLOWED_COMMANDS: %s
                %s

                Output ONLY the raw %s script body. No explanation. No markdown fences.
                """.formatted(
                shell,
                request.getSopStepDescription(),
                request.getSopCategory(),
                request.getSopTitle(),
                request.getTargetHost(),
                allowedCommands,
                request.getAdditionalContext() != null && !request.getAdditionalContext().isBlank()
                        ? "ADDITIONAL_CONTEXT: " + request.getAdditionalContext() : "",
                shell
        );
    }

    private String buildRequestBody(SopScriptRequest request) throws IOException {
        String userMessage = buildUserMessage(request);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = objectMapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);
        messages.add(sysMsg);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        return objectMapper.writeValueAsString(body);
    }

    private String extractContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isEmpty()) {
            throw new ScriptGenerationException("LLM response contained no choices");
        }
        String content = choices.get(0).path("message").path("content").asText("").trim();
        if (content.isBlank()) {
            throw new ScriptGenerationException("LLM returned an empty script");
        }
        // Strip markdown fences if LLM added them despite instructions
        return content
                .replaceAll("(?im)^```[a-z]*\\s*$", "")
                .replaceAll("(?im)^```\\s*$", "")
                .trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP header injection
    // ─────────────────────────────────────────────────────────────────────────

    private String injectMcpHeader(String script, SopScriptRequest request) {
        boolean isWindows = request.isWindows();
        String prefix = isWindows ? "#" : "#";

        String shebang = isWindows ? "" : "#!/bin/bash\n";
        String errorMode = isWindows ? "$ErrorActionPreference = \"Stop\"\n\n" : "set -e\n\n";

        String header = shebang
                + prefix + " [MCP] Auto-generated remediation script\n"
                + prefix + " SOP_ID=" + nvl(request.getSopId()) + "\n"
                + prefix + " SOP_TITLE=" + nvl(request.getSopTitle()) + "\n"
                + prefix + " SOP_CATEGORY=" + nvl(request.getSopCategory()) + "\n"
                + prefix + " MCP_HOST=" + nvl(request.getTargetHost()) + "\n"
                + prefix + " GENERATED_AT=" + Instant.now() + "\n"
                + prefix + " WARNING: This script was auto-generated. Do not modify without SRE review.\n"
                + errorMode;

        // If LLM already included shebang/error-mode, strip them from the body
        String body = script
                .replaceFirst("(?m)^#!/bin/bash\\s*$\\n?", "")
                .replaceFirst("(?m)^#!/usr/bin/env bash\\s*$\\n?", "")
                .replaceFirst("(?m)^set -e\\s*$\\n?", "")
                .replaceFirst("(?m)^\\$ErrorActionPreference.*$\\n?", "")
                .trim();

        return header + body + "\n";
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Built-in template fallback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a keyword-matched built-in script template when the LLM is unavailable.
     * Templates are minimal, safe, and cover the most common remediation patterns.
     */
    private String getTemplateFallback(SopScriptRequest request) {
        String desc = request.getSopStepDescription() != null
                ? request.getSopStepDescription().toLowerCase() : "";
        boolean isWindows = request.isWindows();

        log.info("[ScriptGen] Using template fallback for category='{}' desc='{}'",
                request.getSopCategory(), desc);

        if (!isWindows) {
            // ── Linux templates ────────────────────────────────────────────
            if (desc.contains("tomcat")) {
                return """
                        echo "[MCP] Stopping Tomcat..."
                        systemctl stop tomcat || true
                        sleep 5
                        echo "[MCP] Starting Tomcat..."
                        systemctl start tomcat
                        sleep 3
                        echo "[MCP] Verifying Tomcat is running..."
                        systemctl is-active tomcat
                        echo "[MCP] Tomcat restart complete"
                        """;
            }
            if (desc.contains("nginx")) {
                return """
                        echo "[MCP] Reloading Nginx configuration..."
                        nginx -t
                        systemctl reload nginx
                        echo "[MCP] Nginx reload complete"
                        """;
            }
            if (desc.contains("redis") || desc.contains("cache") || desc.contains("flush")) {
                return """
                        echo "[MCP] Flushing Redis cache..."
                        redis-cli FLUSHALL
                        echo "[MCP] Cache flush complete"
                        """;
            }
            if (desc.contains("postgres") || desc.contains("query") || desc.contains("connection")) {
                return """
                        echo "[MCP] Terminating long-running PostgreSQL queries..."
                        psql -U postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '5 minutes';"
                        echo "[MCP] Done"
                        """;
            }
            if (desc.contains("disk") || desc.contains("log") || desc.contains("cleanup") || desc.contains("clean")) {
                return """
                        echo "[MCP] Cleaning up old log files..."
                        find /var/log -name "*.log" -mtime +7 -exec gzip -f {} \\;
                        find /var/log -name "*.log.gz" -mtime +30 -delete
                        df -h /
                        echo "[MCP] Disk cleanup complete"
                        """;
            }
            if (desc.contains("kubectl") || desc.contains("deploy") || desc.contains("rollout")) {
                return """
                        echo "[MCP] Checking deployment status..."
                        kubectl get pods
                        echo "[MCP] Done"
                        """;
            }
            // Generic Linux service restart
            if (desc.contains("restart") || desc.contains("service")) {
                String svcHint = request.getSopTitle() != null
                        ? request.getSopTitle().toLowerCase().replaceAll("[^a-z0-9]", "") : "app";
                return """
                        echo "[MCP] Restarting service: %s ..."
                        systemctl restart %s
                        sleep 2
                        systemctl is-active %s
                        echo "[MCP] Service restart complete"
                        """.formatted(svcHint, svcHint, svcHint);
            }
        } else {
            // ── Windows templates ──────────────────────────────────────────
            if (desc.contains("iis") || desc.contains("w3svc")) {
                return """
                        Write-Host "[MCP] Restarting IIS..."
                        iisreset /restart /timeout:60
                        Write-Host "[MCP] IIS restart complete"
                        """;
            }
            if (desc.contains("tomcat") || desc.contains("service")) {
                return """
                        Write-Host "[MCP] Restarting Windows service..."
                        Stop-Service -Name "Tomcat9" -Force -ErrorAction SilentlyContinue
                        Start-Sleep -Seconds 5
                        Start-Service -Name "Tomcat9"
                        Get-Service -Name "Tomcat9" | Select-Object Status
                        Write-Host "[MCP] Service restart complete"
                        """;
            }
            if (desc.contains("task") || desc.contains("job") || desc.contains("batch")) {
                return """
                        Write-Host "[MCP] Running scheduled task..."
                        schtasks /Run /TN "ScheduledTask"
                        Write-Host "[MCP] Task triggered"
                        """;
            }
        }

        // Last-resort: safe diagnostic-only script
        log.warn("[ScriptGen] No matching template found — returning diagnostic-only fallback");
        if (isWindows) {
            return """
                    Write-Host "[MCP] No specific template available. Running diagnostic..."
                    Get-Service | Where-Object {$_.Status -eq 'Stopped'} | Select-Object Name, Status
                    Write-Host "[MCP] Diagnostic complete"
                    """;
        } else {
            return """
                    echo "[MCP] No specific template available. Running diagnostic..."
                    systemctl --failed --no-pager
                    df -h
                    echo "[MCP] Diagnostic complete"
                    """;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Typed exception
    // ─────────────────────────────────────────────────────────────────────────

    public static class ScriptGenerationException extends RuntimeException {
        public ScriptGenerationException(String msg) { super(msg); }
        public ScriptGenerationException(String msg, Throwable cause) { super(msg, cause); }
    }
}
