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
 * ItsmTools — spec §5 "Tool Definitions: ITSM Integration".
 *
 * Registers ITSM / ticketing integration tools:
 *   UPDATE_TICKET      — update a ServiceNow / Freshservice ticket
 *   ASSIGN_TICKET      — reassign ticket to a team / engineer
 *   CLOSE_TICKET       — resolve and close a ticket
 *   ADD_COMMENT        — append a work note / comment to a ticket
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mcp.tools.default-definitions.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ItsmTools {

    private final McpToolRegistry registry;

    @PostConstruct
    public void register() {
        // UPDATE_TICKET
        registry.register(
                new ToolDefinition("UPDATE_TICKET", "Update a ticket in ServiceNow or Freshservice",
                        "ITSM", List.of("ticketId", "fields"), false),
                (params, dryRun) -> {
                    String ticketId = (String) params.get("ticketId");
                    Object fields   = params.get("fields");
                    if (dryRun) return Map.of("simulated", true, "ticketId", ticketId, "fields", fields);
                    // TODO: inject ServiceNowClient / FreshserviceClient
                    log.info("STUB UpdateTicket: ticketId={} fields={}", ticketId, fields);
                    return Map.of("updated", ticketId, "status", "OK");
                }
        );

        // ASSIGN_TICKET
        registry.register(
                new ToolDefinition("ASSIGN_TICKET", "Reassign ticket to a team or engineer",
                        "ITSM", List.of("ticketId", "assignee"), false),
                (params, dryRun) -> {
                    String ticketId = (String) params.get("ticketId");
                    String assignee = (String) params.get("assignee");
                    if (dryRun) return Map.of("simulated", true, "ticketId", ticketId, "assignee", assignee);
                    log.info("STUB AssignTicket: ticketId={} assignee={}", ticketId, assignee);
                    return Map.of("assigned", ticketId, "to", assignee, "status", "OK");
                }
        );

        // CLOSE_TICKET
        registry.register(
                new ToolDefinition("CLOSE_TICKET", "Resolve and close a ticket",
                        "ITSM", List.of("ticketId", "resolution"), false),
                (params, dryRun) -> {
                    String ticketId   = (String) params.get("ticketId");
                    String resolution = (String) params.get("resolution");
                    if (dryRun) return Map.of("simulated", true, "ticketId", ticketId);
                    log.info("STUB CloseTicket: ticketId={} resolution='{}'", ticketId, resolution);
                    return Map.of("closed", ticketId, "resolution", resolution, "status", "OK");
                }
        );

        // ADD_COMMENT
        registry.register(
                new ToolDefinition("ADD_COMMENT", "Append a work note to a ticket",
                        "ITSM", List.of("ticketId", "comment"), false),
                (params, dryRun) -> {
                    String ticketId = (String) params.get("ticketId");
                    String comment  = (String) params.get("comment");
                    if (dryRun) return Map.of("simulated", true, "ticketId", ticketId);
                    log.info("STUB AddComment: ticketId={}", ticketId);
                    return Map.of("commented", ticketId, "status", "OK");
                }
        );
    }
}
