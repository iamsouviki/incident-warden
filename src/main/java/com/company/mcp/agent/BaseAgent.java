package com.company.mcp.agent;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Abstract base class for all incident automation agents.
 * Provides common lifecycle and utility methods.
 */
@Slf4j
public abstract class BaseAgent {
    @Getter
    protected final String agentName;

    protected BaseAgent(String agentName) {
        this.agentName = agentName;
    }

    /**
     * Execute the agent's logic on the given context.
     * Each agent processes the context and enriches it with its results.
     *
     * @param context The agent context to process
     * @return Updated context after agent processing
     * @throws AgentExecutionException If the agent encounters a critical error
     */
    public abstract AgentContext execute(AgentContext context) throws AgentExecutionException;

    /**
     * Validate preconditions before agent execution.
     * Allows agents to check if they have required data.
     *
     * @param context The agent context
     * @return true if agent can execute, false otherwise
     */
    public abstract boolean canExecute(AgentContext context);

    /**
     * Get the execution priority (lower = higher priority).
     * Used by orchestrator to determine agent execution order.
     *
     * @return Priority level (1-10, where 1 is highest)
     */
    public abstract int getPriority();

    /**
     * Log agent execution with context.
     */
    protected void logExecution(AgentContext context, String message) {
        if (context != null && context.getIncident() != null) {
            log.info("[{}] Incident={}, TraceId={}: {}", 
                agentName, 
                context.getIncident().getId(), 
                context.getTraceId(),
                message);
        } else {
            log.info("[{}] {}", agentName, message);
        }
    }

    /**
     * Log agent error with context.
     */
    protected void logError(AgentContext context, String message, Throwable e) {
        if (context != null && context.getIncident() != null) {
            log.error("[{}] Incident={}, TraceId={}: {}", 
                agentName, 
                context.getIncident().getId(), 
                context.getTraceId(),
                message, e);
        } else {
            log.error("[{}] {}", agentName, message, e);
        }
    }

    /**
     * Log agent warning with context.
     */
    protected void logWarning(AgentContext context, String message) {
        log.warn("[{}] Incident={}, TraceId={}: {}", 
            agentName, 
            context.getIncident().getId(), 
            context.getTraceId(),
            message);
    }

    /**
     * Handle execution exception with logging.
     */
    protected void handleException(AgentContext context, Exception e, String operation) 
            throws AgentExecutionException {
        String errorMsg = String.format("%s failed during %s: %s", agentName, operation, e.getMessage());
        logError(context, errorMsg, e);
        context.addError(errorMsg);
        throw new AgentExecutionException(errorMsg, e);
    }

    /**
     * Custom exception for agent execution failures.
     */
    public static class AgentExecutionException extends Exception {
        public AgentExecutionException(String message) {
            super(message);
        }

        public AgentExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
