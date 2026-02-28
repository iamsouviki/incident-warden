package com.company.mcp.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a single action execution step in the action executor agent.
 * Tracks tool invocation, execution status, and results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionExecutionStep {
    private String toolName;
    private LocalDateTime executedAt;
    private boolean success;
    private String result;
    private String errorMessage;
    private int retryCount;
}
