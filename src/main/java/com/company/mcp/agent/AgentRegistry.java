package com.company.mcp.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * Agent Registry - Registers all agents with the orchestrator.
 * Ensures proper agent pipeline configuration on application startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRegistry {
    private final OrchestratorAgent orchestrator;
    private final ClassifierAgent classifierAgent;
    private final PatternMatcherAgent patternMatcherAgent;
    private final SopRankerAgent sopRankerAgent;
    private final ConfidenceScorerAgent confidenceScorerAgent;
    private final RiskEvaluatorAgent riskEvaluatorAgent;
    private final ActionExecutorAgent actionExecutorAgent;
    private final AuditAgent auditAgent;

    /**
     * Register all agents with the orchestrator on application startup.
     */
    @PostConstruct
    public void registerAllAgents() {
        log.info("Registering agents with orchestrator");
        
        orchestrator.registerAgent(classifierAgent);
        orchestrator.registerAgent(patternMatcherAgent);
        orchestrator.registerAgent(sopRankerAgent);
        orchestrator.registerAgent(confidenceScorerAgent);
        orchestrator.registerAgent(riskEvaluatorAgent);
        orchestrator.registerAgent(actionExecutorAgent);
        orchestrator.registerAgent(auditAgent);
        
        log.info("Agent registration completed - {} agents registered", 
            orchestrator.getRegisteredAgents().size());
    }
}
