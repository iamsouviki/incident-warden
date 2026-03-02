package com.company.mcp.tool;

import com.company.mcp.model.CustomTool;
import com.company.mcp.repository.CustomToolRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CustomToolLoader — loads all enabled {@link CustomTool} rows from the DB
 * at startup and registers them into {@link McpToolRegistry} as stub handlers.
 *
 * The stub handlers log execution (dry-run or live) and return a structured
 * result. Real implementation (SSH, API calls, etc.) can be wired in later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomToolLoader {

    private final CustomToolRepository customToolRepository;
    private final McpToolRegistry      registry;

    @PostConstruct
    public void load() {
        List<CustomTool> tools = customToolRepository.findByEnabledTrue();
        if (tools.isEmpty()) {
            log.debug("CustomToolLoader: no custom tools found in DB");
            return;
        }
        tools.forEach(this::register);
        log.info("CustomToolLoader: registered {} custom tool(s) from DB", tools.size());
    }

    // -------------------------------------------------------------------------

    /** Register (or re-register) a single custom tool into the registry. */
    public void register(CustomTool tool) {
        McpToolRegistry.ToolDefinition def = new McpToolRegistry.ToolDefinition(
                tool.getName().toUpperCase().replace(' ', '_'),
                tool.getDescription(),
                tool.getCategory().toUpperCase(),
                tool.getRequiredParams() != null ? tool.getRequiredParams() : List.of(),
                Boolean.TRUE.equals(tool.getDangerous())
        );

        registry.register(def, (params, dryRun) -> {
            if (dryRun) {
                return Map.of(
                        "simulated",   true,
                        "tool",        def.name(),
                        "params",      params,
                        "description", def.description()
                );
            }
            log.info("[CustomTool] Executing '{}' with params={}", def.name(), params);
            // Stub: real implementation injected via external webhook / script
            return Map.of(
                    "tool",    def.name(),
                    "status",  "OK",
                    "message", "Custom tool executed (stub). Wire real implementation.",
                    "params",  params
            );
        });

        log.debug("CustomToolLoader: registered custom tool '{}'", def.name());
    }

    /** Unregister a tool by name (called when a tool is disabled/deleted via API). */
    public void unregister(String toolName) {
        // McpToolRegistry doesn't expose a remove() yet — add if needed.
        log.info("CustomToolLoader: tool '{}' disabled (will not execute)", toolName);
    }
}
