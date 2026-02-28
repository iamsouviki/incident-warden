package com.company.mcp.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * McpToolExecutor — spec §5 "MCP Tool Framework".
 *
 * Secure, audited execution layer that wraps raw {@link McpToolRegistry.ToolHandler}
 * invocations with:
 *   • Parameter validation against ToolDefinition.requiredParams
 *   • Dry-run mode routing
 *   • Configurable per-call timeout
 *   • Structured result envelope
 *   • Execution logging (wire to AuditService for persistence)
 *
 * Result envelope:
 * <pre>
 * {
 *   "tool":       "RESTART_SERVICE",
 *   "status":     "SUCCESS" | "FAILED" | "DRY_RUN",
 *   "dryRun":     true | false,
 *   "startedAt":  "2024-01-01T00:00:00Z",
 *   "durationMs": 123,
 *   "result":     { … tool-specific … },
 *   "error":      "message or null"
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolExecutor {

    private final McpToolRegistry registry;

    @Value("${mcp.tools.timeout-ms:30000}")
    private long defaultTimeoutMs;

    // -------------------------------------------------------------------------
    // Execute
    // -------------------------------------------------------------------------

    /**
     * Execute a registered tool by name.
     *
     * @param toolName  registered tool name (case-insensitive)
     * @param params    tool parameters
     * @param dryRun    if {@code true}, simulate without side effects
     * @return structured result envelope
     */
    public Map<String, Object> execute(String toolName,
                                       Map<String, Object> params,
                                       boolean dryRun) {

        Instant started = Instant.now();
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("tool",      toolName);
        envelope.put("dryRun",    dryRun);
        envelope.put("startedAt", started.toString());

        McpToolRegistry.ToolDefinition def = registry.getDefinition(toolName).orElse(null);

        if (def == null) {
            envelope.put("status", "FAILED");
            envelope.put("error",  "Tool not registered: " + toolName);
            log.warn("McpToolExecutor: unknown tool '{}'", toolName);
            return envelope;
        }

        // Parameter validation
        String validationError = validate(def, params);
        if (validationError != null) {
            envelope.put("status", "FAILED");
            envelope.put("error",  validationError);
            return envelope;
        }

        McpToolRegistry.ToolHandler handler = registry.getHandler(toolName).orElseThrow();

        try {
            Map<String, Object> result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return handler.execute(params, dryRun);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .get(defaultTimeoutMs, TimeUnit.MILLISECONDS);

            envelope.put("status",     dryRun ? "DRY_RUN" : "SUCCESS");
            envelope.put("result",     result);
            envelope.put("error",      null);
            log.info("McpToolExecutor: tool='{}' dryRun={} status=SUCCESS durationMs={}",
                    toolName, dryRun, elapsed(started));

        } catch (TimeoutException e) {
            envelope.put("status", "FAILED");
            envelope.put("error",  "Tool execution timed out after " + defaultTimeoutMs + "ms");
            log.error("McpToolExecutor: tool='{}' TIMEOUT", toolName);

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            envelope.put("status", "FAILED");
            envelope.put("error",  cause.getMessage());
            log.error("McpToolExecutor: tool='{}' FAILED: {}", toolName, cause.getMessage(), cause);
        }

        envelope.put("durationMs", elapsed(started));
        return envelope;
    }

    // -------------------------------------------------------------------------

    private String validate(McpToolRegistry.ToolDefinition def, Map<String, Object> params) {
        if (def.requiredParams() == null || def.requiredParams().isEmpty()) return null;
        for (String required : def.requiredParams()) {
            if (params == null || !params.containsKey(required) || params.get(required) == null) {
                return "Missing required parameter: " + required;
            }
        }
        return null;
    }

    private static long elapsed(Instant start) {
        return Instant.now().toEpochMilli() - start.toEpochMilli();
    }
}
