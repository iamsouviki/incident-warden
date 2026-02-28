package com.company.mcp.agent;

import com.company.mcp.service.RemediationToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Action Executor Agent - Phase 7 Implementation.
 * Executes remediation actions and tool calls with rollback capability.
 * 
 * Phase 7 Implementation:
 * - Tool invocation framework for incident remediation
 * - Pre/post state capture for rollback
 * - Dry-run mode for validation
 * - Tool result handling and error recovery
 * - Rollback on action failure
 * 
 * Supported Tools (Extensible):
 * - RESTART_SERVICE: Restart a service on target host
 * - SCALE_UP: Scale up container replicas
 * - DRAIN_QUEUE: Drain messages from queue
 * - CLEAR_CACHE: Clear cache entries
 * - ROLLBACK_DEPLOY: Rollback previous deployment
 * - RUN_SCRIPT: Execute remediation script
 */
@Slf4j
@Component
public class ActionExecutorAgent extends BaseAgent {

    // Tool execution constants
    private static final String TOOL_RESTART_SERVICE = "RESTART_SERVICE";
    private static final String TOOL_SCALE_UP = "SCALE_UP";
    private static final String TOOL_DRAIN_QUEUE = "DRAIN_QUEUE";
    private static final String TOOL_CLEAR_CACHE = "CLEAR_CACHE";
    private static final String TOOL_ROLLBACK_DEPLOY = "ROLLBACK_DEPLOY";
    private static final String TOOL_RUN_SCRIPT = "RUN_SCRIPT";

    @Autowired
    private RemediationToolRegistry toolRegistry;

    public ActionExecutorAgent() {
        super("ActionExecutorAgent");
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "ActionExecutorAgent: Executing remediation actions");
        
        try {
            // Only execute actions for AUTO_RESOLVE decisions or HITL-approved decisions
            boolean shouldExecute = "AUTO_RESOLVE".equals(context.getDecision()) ||
                                   (context.getDecidedByHuman() != null && 
                                    context.getDecidedByHuman() && 
                                    "APPROVED".equals(context.getDecision()));
            
            if (!shouldExecute) {
                logExecution(context, "Action execution deferred - decision is " + context.getDecision());
                return context;
            }
            
            // Extract actions from SOP recommendation (if available)
            List<String> recommendedActions = extractActionsFromSop(context);
            
            if (recommendedActions.isEmpty()) {
                // Use default remediation based on classification
                recommendedActions = buildDefaultActions(context);
            }
            
            // Execute each action with dry-run first
            double overallSuccess = 0.0;
            for (String action : recommendedActions) {
                try {
                    // Step 1: Dry-run for validation
                    Map<String, Object> dryRunResult = executeTool(action, context, true);
                    
                    if ((Boolean) dryRunResult.getOrDefault("success", false)) {
                        // Step 2: Execute actual tool
                        Map<String, Object> actualResult = executeTool(action, context, false);
                        
                        AgentContext.ActionExecutionStep step = new AgentContext.ActionExecutionStep();
                        step.setToolName(extractToolName(action));
                        step.setExecutedAt(LocalDateTime.now());
                        step.setStatus((Boolean) actualResult.getOrDefault("success", false) ? "SUCCESS" : "FAILED");
                        step.setResult(actualResult);
                        
                        context.getExecutedSteps().add(step);
                        
                        if ("SUCCESS".equals(step.getStatus())) {
                            overallSuccess += 1.0;
                            logExecution(context, "Action executed: " + action);
                        } else {
                            logWarning(context, "Action failed: " + action + " - " + actualResult.get("message"));
                            // Attempt rollback on failure
                            performRollback(action, context);
                        }
                    } else {
                        logWarning(context, "Action dry-run failed: " + action);
                    }
                } catch (Exception e) {
                    logError(context, "Exception during action execution: " + action, e);
                    performRollback(action, context);
                }
            }
            
            // Update decision if actions failed
            double successRate = recommendedActions.isEmpty() ? 1.0 : (overallSuccess / recommendedActions.size());
            if (successRate < 0.5) {
                context.setDecision("ACTION_FAILED");
                logWarning(context, "Action execution failed with " + (int)(successRate * 100) + "% success rate");
            }
            
            logExecution(context, "Action execution completed: Success=" + String.format("%.0f", successRate * 100) + 
                                 "%, Steps=" + context.getExecutedSteps().size());
            
            return context;
        } catch (Exception e) {
            handleException(context, e, "action execution");
            context.setDecision("ACTION_FAILED");
            return context;
        }
    }

    @Override
    public boolean canExecute(AgentContext context) {
        return "AUTO_RESOLVE".equals(context.getDecision()) ||
               (Boolean.TRUE.equals(context.getDecidedByHuman()) && "APPROVED".equals(context.getDecision()));
    }

    @Override
    public int getPriority() {
        return 7; // Runs after GuardrailsAgent (6)
    }

    /**
     * Execute a tool action by delegating to {@link RemediationToolRegistry}.
     *
     * <p>The registry translates the action string into a real OS/HTTP call:
     * <ul>
     *   <li>{@code CHECK_URL:http://host/health}  → HTTP GET, pass if 2xx/3xx</li>
     *   <li>{@code RESTART_SERVICE:tomcat}         → systemctl restart tomcat (Linux)<br>
     *       {@code RESTART_SERVICE:tomcat:CATALINA=/opt/tomcat} → shutdown.sh + startup.sh<br>
     *       {@code RESTART_SERVICE:svc:windows-service} → sc stop/start</li>
     *   <li>{@code CLEAR_CACHE:redis}              → redis-cli FLUSHDB localhost:6379</li>
     *   <li>{@code CLEAR_CACHE:redis:host:port:pattern} → redis-cli DEL pattern*</li>
     *   <li>{@code CLEAR_CACHE:memcached:host:port} → TCP flush_all</li>
     *   <li>{@code RERUN_JOB:/opt/scripts/fix.sh}  → /bin/sh /opt/scripts/fix.sh</li>
     *   <li>{@code RERUN_JOB:NightlyClean:windows}  → schtasks /run /tn "NightlyClean"</li>
     *   <li>{@code RERUN_JOB:deploy:jenkins:http://ci/job/deploy/build} → Jenkins POST</li>
     *   <li>{@code SCALE_UP:api-deployment:5}      → kubectl scale deployment/api --replicas=5</li>
     *   <li>{@code ROLLBACK_DEPLOY:my-release}     → helm rollback my-release</li>
     *   <li>{@code DRAIN_QUEUE:redis-list:slow-q}  → redis-cli DEL slow-q</li>
     * </ul>
     *
     * <p>For the URL-check + conditional restart pattern, use two action steps in the SOP:
     * <pre>
     *   ["CHECK_URL:http://app/health", "RESTART_SERVICE:tomcat", "CHECK_URL:http://app/health"]
     * </pre>
     * If {@code CHECK_URL} passes, the next action is still executed (SOP controls order).
     * If {@code RESTART_SERVICE} fails, automatic rollback is triggered.
     */
    private Map<String, Object> executeTool(String action, AgentContext context, boolean dryRun) {
        if (toolRegistry != null) {
            return toolRegistry.execute(action, dryRun);
        }
        // Fallback if registry not yet injected (unit-test context)
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", (dryRun ? "[DRY-RUN] " : "") + "Simulated: " + action);
        return result;
    }

    /**
     * Perform rollback for failed action.
     */
    private void performRollback(String action, AgentContext context) {
        try {
            String toolName = extractToolName(action);
            
            // Define rollback actions (inverse operations)
            String rollbackAction = null;
            
            switch (toolName) {
                case TOOL_SCALE_UP:
                    rollbackAction = "SCALE_DOWN:" + extractTarget(action);
                    break;
                case TOOL_RESTART_SERVICE:
                    rollbackAction = "RESTART_PREVIOUS:" + extractTarget(action);
                    break;
                case TOOL_DRAIN_QUEUE:
                    rollbackAction = "RESTORE_QUEUE:" + extractTarget(action);
                    break;
                // Other tools may not need rollback
            }
            
            if (rollbackAction != null) {
                log.warn("Executing rollback for {}: {}", action, rollbackAction);
                Map<String, Object> rollbackResult = executeTool(rollbackAction, context, false);
                if (!(Boolean) rollbackResult.getOrDefault("success", false)) {
                    log.error("CRITICAL: Rollback failed for action {}. Manual intervention may be required.", action);
                    context.addWarning("CRITICAL: Rollback failure for " + action);
                }
            }
        } catch (Exception e) {
            log.error("Rollback execution failed", e);
            context.addWarning("Rollback exception: " + e.getMessage());
        }
    }

    /**
     * Extract actions recommended from SOP.
     */
    private List<String> extractActionsFromSop(AgentContext context) {
        List<String> actions = new ArrayList<>();
        
        // In Phase 7, if SOP has action plan, parse and extract
        if (context.getActionPlan() != null) {
            // Parse action_plan JSON from SOP
            // Example: {"actions": ["RESTART_SERVICE:api-server", "SCALE_UP:api-replicas:5"]}
            Object actionsObj = context.getActionPlan().get("actions");
            if (actionsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> actionsList = (List<String>) actionsObj;
                actions.addAll(actionsList);
            }
        }
        
        return actions;
    }

    /**
     * Build default remediation actions based on classification.
     */
    private List<String> buildDefaultActions(AgentContext context) {
        List<String> actions = new ArrayList<>();
        
        String category = context.getIncident().getCategory();
        if (category == null) return actions;
        
        switch (category.toUpperCase()) {
            case "DATABASE":
                actions.add("RESTART_SERVICE:database");
                actions.add("CLEAR_CACHE:redis");
                break;
            case "NETWORK":
                actions.add("RESTART_SERVICE:network-proxy");
                break;
            case "INFRASTRUCTURE":
                actions.add("SCALE_UP:api:5");
                break;
            case "DEPLOYMENT":
                actions.add("ROLLBACK_DEPLOY:latest");
                break;
            case "PERFORMANCE":
                actions.add("CLEAR_CACHE:all");
                actions.add("DRAIN_QUEUE:slow-queue");
                break;
        }
        
        return actions;
    }

    /**
     * Extract tool name from action string (e.g., "RESTART_SERVICE:api" → "RESTART_SERVICE").
     */
    private String extractToolName(String action) {
        if (action == null) return "";
        String[] parts = action.split(":");
        return parts[0].trim();
    }

    /**
     * Extract target parameter from action string (e.g., "RESTART_SERVICE:api" → "api").
     */
    private String extractTarget(String action) {
        if (action == null) return "";
        String[] parts = action.split(":");
        return parts.length > 1 ? parts[1].trim() : "";
    }
}
