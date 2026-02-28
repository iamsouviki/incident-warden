package com.company.mcp.tool.definitions;

import com.company.mcp.tool.McpToolRegistry;
import com.company.mcp.tool.McpToolRegistry.ToolDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MonitoringTools — spec §5 "Tool Definitions: Monitoring".
 *
 * Registers monitoring / observability tools:
 *   GET_METRICS        — retrieve current metrics from Prometheus
 *   SILENCE_ALERT      — silence an Alertmanager alert
 *   CREATE_INCIDENT    — manually create a new tracking incident
 *   GET_LOGS           — fetch recent log lines from Loki / ELK
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringTools {

    private final McpToolRegistry registry;

    @PostConstruct
    public void register() {
        // GET_METRICS
        registry.register(
                new ToolDefinition("GET_METRICS", "Retrieve service metrics from Prometheus",
                        "MONITORING", List.of("query"), false),
                (params, dryRun) -> {
                    String query = (String) params.get("query");
                    if (dryRun) return Map.of("simulated", true, "query", query);
                    // TODO: inject PrometheusClient and execute PromQL query
                    log.info("STUB GetMetrics: query={}", query);
                    return Map.of("query", query, "result", List.of(), "status", "OK");
                }
        );

        // SILENCE_ALERT
        registry.register(
                new ToolDefinition("SILENCE_ALERT", "Silence an Alertmanager alert",
                        "MONITORING", List.of("alertName", "durationMinutes"), false),
                (params, dryRun) -> {
                    String alert    = (String) params.get("alertName");
                    Object duration = params.get("durationMinutes");
                    if (dryRun) return Map.of("simulated", true, "alert", alert, "durationMinutes", duration);
                    // TODO: inject AlertmanagerClient
                    log.info("STUB SilenceAlert: alert={} durationMin={}", alert, duration);
                    return Map.of("silenced", alert, "durationMinutes", duration, "status", "OK");
                }
        );

        // CREATE_INCIDENT
        registry.register(
                new ToolDefinition("CREATE_INCIDENT", "Manually create a tracking incident",
                        "MONITORING", List.of("title", "severity"), false),
                (params, dryRun) -> {
                    String title    = (String) params.get("title");
                    String severity = (String) params.get("severity");
                    if (dryRun) return Map.of("simulated", true, "title", title, "severity", severity);
                    log.info("STUB CreateIncident: title='{}' severity={}", title, severity);
                    return Map.of("created", true, "title", title, "severity", severity, "status", "OK");
                }
        );

        // GET_LOGS
        registry.register(
                new ToolDefinition("GET_LOGS", "Fetch recent log lines from Loki / ELK",
                        "MONITORING", List.of("serviceName"), false),
                (params, dryRun) -> {
                    String service = (String) params.get("serviceName");
                    Object lines   = params.getOrDefault("maxLines", 100);
                    if (dryRun) return Map.of("simulated", true, "service", service);
                    // TODO: inject LokiClient / ElasticsearchClient
                    log.info("STUB GetLogs: service={} maxLines={}", service, lines);
                    return Map.of("service", service, "logs", List.of(), "status", "OK");
                }
        );
    }
}
