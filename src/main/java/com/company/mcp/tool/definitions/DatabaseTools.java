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
 * DatabaseTools — spec §5 "Tool Definitions: Database".
 *
 * Registers database maintenance tools:
 *   CLEAR_CACHE        — flush application / DB cache
 *   DRAIN_QUEUE        — drain a message queue
 *   KILL_QUERY         — terminate a long-running DB query (dangerous)
 *   VACUUM_TABLE       — run VACUUM ANALYZE on a table
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseTools {

    private final McpToolRegistry registry;

    @PostConstruct
    public void register() {
        // CLEAR_CACHE
        registry.register(
                new ToolDefinition("CLEAR_CACHE", "Flush application or DB query cache",
                        "DATABASE", List.of("cacheTarget"), false),
                (params, dryRun) -> {
                    String target = (String) params.get("cacheTarget");
                    if (dryRun) return Map.of("simulated", true, "action", "clear_cache", "target", target);
                    log.info("STUB ClearCache: target={}", target);
                    return Map.of("cleared", target, "status", "OK");
                }
        );

        // DRAIN_QUEUE
        registry.register(
                new ToolDefinition("DRAIN_QUEUE", "Drain a message / work queue",
                        "DATABASE", List.of("queueName"), false),
                (params, dryRun) -> {
                    String queue = (String) params.get("queueName");
                    if (dryRun) return Map.of("simulated", true, "action", "drain_queue", "queue", queue);
                    log.info("STUB DrainQueue: queue={}", queue);
                    return Map.of("drained", queue, "messagesRemoved", 0, "status", "OK");
                }
        );

        // KILL_QUERY
        registry.register(
                new ToolDefinition("KILL_QUERY", "Terminate a long-running DB query",
                        "DATABASE", List.of("queryPid"), true),
                (params, dryRun) -> {
                    Object pid = params.get("queryPid");
                    if (dryRun) return Map.of("simulated", true, "action", "kill_query", "pid", pid);
                    log.warn("STUB KillQuery (DANGEROUS): pid={}", pid);
                    return Map.of("killed", pid, "status", "OK");
                }
        );

        // VACUUM_TABLE
        registry.register(
                new ToolDefinition("VACUUM_TABLE", "Run VACUUM ANALYZE on a table",
                        "DATABASE", List.of("tableName"), false),
                (params, dryRun) -> {
                    String table = (String) params.get("tableName");
                    if (dryRun) return Map.of("simulated", true, "action", "vacuum", "table", table);
                    log.info("STUB VacuumTable: table={}", table);
                    return Map.of("vacuumed", table, "status", "OK");
                }
        );
    }
}
