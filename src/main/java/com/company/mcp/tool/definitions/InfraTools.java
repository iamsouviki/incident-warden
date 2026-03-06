package com.company.mcp.tool.definitions;

import com.company.mcp.tool.McpToolRegistry;
import com.company.mcp.tool.McpToolRegistry.ToolDefinition;
import com.company.mcp.tool.McpToolRegistry.ToolHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * InfraTools — spec §5 "Tool Definitions: Infrastructure".
 *
 * Registers infrastructure remediation tools:
 *   RESTART_SERVICE   — restart a service on target host / k8s pod
 *   SCALE_UP          — increase replica count
 *   SCALE_DOWN        — decrease replica count
 *   ROLLBACK_DEPLOY   — rollback to previous deployment
 *   RUN_SCRIPT        — run an approved runbook script (dangerous)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.tools.default-definitions.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InfraTools {

    private final McpToolRegistry registry;

    @PostConstruct
    public void register() {
        // RESTART_SERVICE
        registry.register(
                new ToolDefinition("RESTART_SERVICE", "Restart a service or k8s pod",
                        "INFRA", List.of("serviceName"), false),
                (params, dryRun) -> {
                    String service = (String) params.get("serviceName");
                    if (dryRun) {
                        return Map.of("simulated", true, "action", "restart", "service", service);
                    }
                    // TODO: inject KubernetesClient / SSH executor
                    log.info("STUB RestartService: service={}", service);
                    return Map.of("restarted", service, "status", "OK");
                }
        );

        // SCALE_UP
        registry.register(
                new ToolDefinition("SCALE_UP", "Scale up container replicas",
                        "INFRA", List.of("serviceName", "replicas"), false),
                (params, dryRun) -> {
                    String service  = (String) params.get("serviceName");
                    Object replicas = params.get("replicas");
                    if (dryRun) {
                        return Map.of("simulated", true, "action", "scale_up",
                                "service", service, "replicas", replicas);
                    }
                    log.info("STUB ScaleUp: service={} replicas={}", service, replicas);
                    return Map.of("scaled", service, "replicas", replicas, "status", "OK");
                }
        );

        // SCALE_DOWN
        registry.register(
                new ToolDefinition("SCALE_DOWN", "Scale down container replicas",
                        "INFRA", List.of("serviceName", "replicas"), false),
                (params, dryRun) -> {
                    String service  = (String) params.get("serviceName");
                    Object replicas = params.get("replicas");
                    if (dryRun) {
                        return Map.of("simulated", true, "action", "scale_down",
                                "service", service, "replicas", replicas);
                    }
                    log.info("STUB ScaleDown: service={} replicas={}", service, replicas);
                    return Map.of("scaled", service, "replicas", replicas, "status", "OK");
                }
        );

        // ROLLBACK_DEPLOY
        registry.register(
                new ToolDefinition("ROLLBACK_DEPLOY", "Rollback service to previous deployment",
                        "INFRA", List.of("serviceName"), true),
                (params, dryRun) -> {
                    String service = (String) params.get("serviceName");
                    if (dryRun) {
                        return Map.of("simulated", true, "action", "rollback", "service", service);
                    }
                    log.warn("STUB RollbackDeploy (DANGEROUS): service={}", service);
                    return Map.of("rolledBack", service, "status", "OK");
                }
        );

        // RUN_SCRIPT
        registry.register(
                new ToolDefinition("RUN_SCRIPT", "Execute an approved runbook script",
                        "INFRA", List.of("scriptName"), true),
                (params, dryRun) -> {
                    String script = (String) params.get("scriptName");
                    if (dryRun) {
                        return Map.of("simulated", true, "action", "run_script", "script", script);
                    }
                    log.warn("STUB RunScript (DANGEROUS): script={}", script);
                    return Map.of("script", script, "exitCode", 0, "status", "OK");
                }
        );
    }
}
