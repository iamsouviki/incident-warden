package com.company.mcp.tool;

import com.company.mcp.model.CustomTool;
import com.company.mcp.model.ScriptWorkspace;
import com.company.mcp.repository.CustomToolRepository;
import com.company.mcp.repository.ScriptWorkspaceRepository;
import com.company.mcp.service.StoredScriptExecutionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CustomToolLoader — loads all enabled {@link CustomTool} rows from the DB
 * at startup and registers them into {@link McpToolRegistry}.
 *
 * When a custom tool is linked to a Script Workspace entry, the registered
 * handler executes that stored script with the same guardrail and remote/local
 * execution path used by the script editor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomToolLoader {

    private final CustomToolRepository customToolRepository;
    private final ScriptWorkspaceRepository scriptWorkspaceRepository;
    private final StoredScriptExecutionService storedScriptExecutionService;
    private final McpToolRegistry registry;

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
            if (tool.getScriptWorkspaceId() == null) {
                return Map.of(
                        "simulated",   dryRun,
                        "tool",        def.name(),
                        "params",      params,
                        "description", def.description(),
                        "message",     "Custom tool has no linked script; returning metadata only"
                );
            }

            ScriptWorkspace scriptWorkspace = scriptWorkspaceRepository.findById(tool.getScriptWorkspaceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Linked script not found for tool '" + def.name() + "'"));

            log.info("[CustomTool] Executing '{}' via scriptWorkspace={} params={}",
                    def.name(), scriptWorkspace.getId(), params);

            StoredScriptExecutionService.ScriptRunResult result =
                    storedScriptExecutionService.execute(scriptWorkspace, dryRun);

            updateScriptExecutionState(scriptWorkspace, result);

            if (!result.success()) {
                throw new IllegalStateException(result.message() + ": " + result.stderr());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tool", def.name());
            response.put("scriptWorkspaceId", scriptWorkspace.getId());
            response.put("message", result.message());
            response.put("remote", result.remote());
            response.put("targetHost", result.targetHost());
            response.put("exitCode", result.exitCode());
            response.put("stdout", result.stdout());
            response.put("stderr", result.stderr());
            if (result.credentialSource() != null) {
                response.put("credentialSource", result.credentialSource());
            }
            return response;
        });

        log.debug("CustomToolLoader: registered custom tool '{}'", def.name());
    }

    /** Unregister a tool by name (called when a tool is disabled/deleted via API). */
    public void unregister(String toolName) {
        registry.unregister(toolName);
        log.info("CustomToolLoader: unregistered custom tool '{}'", toolName);
    }

    private void updateScriptExecutionState(
            ScriptWorkspace scriptWorkspace,
            StoredScriptExecutionService.ScriptRunResult result) {
        scriptWorkspace.setLastExecutionOutput(joinOutputs(result.stdout(), result.stderr()));
        scriptWorkspace.setLastExecutionExitCode(result.exitCode());
        scriptWorkspace.setLastExecutedAt(LocalDateTime.now());
        scriptWorkspace.setStatus(result.success()
                ? (result.dryRun() ? "VALIDATED" : "EXECUTED")
                : "FAILED");
        scriptWorkspace.setUpdatedAt(LocalDateTime.now());
        scriptWorkspaceRepository.save(scriptWorkspace);
    }

    private static String joinOutputs(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout.trim();
        String err = stderr == null ? "" : stderr.trim();
        if (out.isBlank()) {
            return err;
        }
        if (err.isBlank()) {
            return out;
        }
        return out + "\n\n[stderr]\n" + err;
    }
}
