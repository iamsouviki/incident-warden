package com.company.mcp.tool.definitions;

import com.company.mcp.tool.McpToolRegistry;
import com.company.mcp.tool.McpToolRegistry.ToolDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * NotificationTools — spec §5 "Tool Definitions: Notifications".
 *
 * Registers outbound notification tools:
 *   SEND_SLACK         — post a message to a Slack channel
 *   SEND_EMAIL         — send an email alert
 *   TRIGGER_PAGERDUTY  — create a PagerDuty incident
 *   SEND_TEAMS         — post a message to Microsoft Teams
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.tools.default-definitions.enabled", havingValue = "true")
@RequiredArgsConstructor
public class NotificationTools {

    private final McpToolRegistry registry;

    @PostConstruct
    public void register() {
        // SEND_SLACK
        registry.register(
                new ToolDefinition("SEND_SLACK", "Post a message to a Slack channel",
                        "NOTIFICATION", List.of("channel", "message"), false),
                (params, dryRun) -> {
                    String channel = (String) params.get("channel");
                    String message = (String) params.get("message");
                    if (dryRun) return Map.of("simulated", true, "channel", channel);
                    // TODO: inject Slack WebClient
                    log.info("STUB SendSlack: channel={} message='{}'", channel, message);
                    return Map.of("channel", channel, "ts", System.currentTimeMillis(), "status", "OK");
                }
        );

        // SEND_EMAIL
        registry.register(
                new ToolDefinition("SEND_EMAIL", "Send an alert email",
                        "NOTIFICATION", List.of("to", "subject", "body"), false),
                (params, dryRun) -> {
                    String to      = (String) params.get("to");
                    String subject = (String) params.get("subject");
                    if (dryRun) return Map.of("simulated", true, "to", to, "subject", subject);
                    // TODO: inject JavaMailSender
                    log.info("STUB SendEmail: to={} subject='{}'", to, subject);
                    return Map.of("to", to, "status", "OK");
                }
        );

        // TRIGGER_PAGERDUTY
        registry.register(
                new ToolDefinition("TRIGGER_PAGERDUTY", "Create a PagerDuty incident",
                        "NOTIFICATION", List.of("summary", "severity"), false),
                (params, dryRun) -> {
                    String summary  = (String) params.get("summary");
                    String severity = (String) params.get("severity");
                    if (dryRun) return Map.of("simulated", true, "summary", summary);
                    // TODO: inject PagerDuty Events v2 client
                    log.info("STUB TriggerPagerDuty: summary='{}' severity={}", summary, severity);
                    return Map.of("dedupKey", "mcp-" + System.currentTimeMillis(), "status", "triggered");
                }
        );

        // SEND_TEAMS
        registry.register(
                new ToolDefinition("SEND_TEAMS", "Post a message to Microsoft Teams",
                        "NOTIFICATION", List.of("webhookUrl", "message"), false),
                (params, dryRun) -> {
                    String webhook = (String) params.get("webhookUrl");
                    String message = (String) params.get("message");
                    if (dryRun) return Map.of("simulated", true, "webhook", webhook);
                    // TODO: inject RestTemplate / WebClient for Teams webhook
                    log.info("STUB SendTeams: webhook={}", webhook);
                    return Map.of("posted", true, "status", "OK");
                }
        );
    }
}
