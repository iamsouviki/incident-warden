package com.company.mcp.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * McpToolRegistry — spec §5 "MCP Tool Framework".
 *
 * Central registry for all MCP-callable tools.  Tool providers (DatabaseTools,
 * InfraTools, etc.) register their handlers at startup; the ActionExecutorAgent
 * and McpToolExecutor look up handlers by tool name at runtime.
 *
 * Thread-safe: uses {@link ConcurrentHashMap}.
 *
 * Tool names are case-insensitive and normalised to UPPER_SNAKE_CASE.
 */
@Slf4j
@Component
public class McpToolRegistry {

    /** Handler function type: accepts params map + dry-run flag, returns result map. */
    @FunctionalInterface
    public interface ToolHandler {
        Map<String, Object> execute(Map<String, Object> params, boolean dryRun) throws Exception;
    }

    private final Map<String, ToolHandler>   handlers    = new ConcurrentHashMap<>();
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Register a tool with its definition and handler.
     *
     * @param definition metadata (name, description, category, requiredParams)
     * @param handler    execution logic
     */
    public void register(ToolDefinition definition, ToolHandler handler) {
        String key = normalise(definition.name());
        handlers.put(key, handler);
        definitions.put(key, definition);
        log.info("McpToolRegistry: registered tool '{}' [{}]", definition.name(), definition.category());
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    public Optional<ToolHandler> getHandler(String toolName) {
        return Optional.ofNullable(handlers.get(normalise(toolName)));
    }

    public Optional<ToolDefinition> getDefinition(String toolName) {
        return Optional.ofNullable(definitions.get(normalise(toolName)));
    }

    public boolean isRegistered(String toolName) {
        return handlers.containsKey(normalise(toolName));
    }

    /** All registered tool definitions (for UI / API discovery). */
    public Collection<ToolDefinition> allDefinitions() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    // -------------------------------------------------------------------------

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toUpperCase();
    }

    // -------------------------------------------------------------------------
    // Tool definition record
    // -------------------------------------------------------------------------

    /**
     * Immutable metadata record for a registered tool.
     *
     * @param name           unique tool name (UPPER_SNAKE_CASE)
     * @param description    human-readable description
     * @param category       grouping category (INFRA, DATABASE, MONITORING, …)
     * @param requiredParams required parameter keys
     * @param dangerous      if true, requires HITL confirmation by default
     */
    public record ToolDefinition(
            String name,
            String description,
            String category,
            java.util.List<String> requiredParams,
            boolean dangerous
    ) {}
}
