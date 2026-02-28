package com.company.mcp.agent;

import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agent Pipeline Service - Orchestrates incident processing through the multi-agent system.
 * Manages context creation, orchestrator invocation, and result persistence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPipeline {
    private final OrchestratorAgent orchestrator;
    private final IncidentRepository incidentRepository;

    /**
     * Process a single incident through the entire agent pipeline.
     * 
     * @param incident The incident to process
     * @param tenantId The tenant ID for multi-tenancy
     * @return The final agent context after all processing
     */
    public AgentContext processIncident(Incident incident, String tenantId) {
        String traceId = UUID.randomUUID().toString();
        
        try {
            log.info("Starting incident processing pipeline - IncidentId={}, TraceId={}", incident.getId(), traceId);

            // Create agent context
            AgentContext context = AgentContext.builder()
                .incident(incident)
                .tenantId(tenantId)
                .traceId(traceId)
                .processingStartedAt(LocalDateTime.now())
                .build();

            // Execute orchestrator (which coordinates all agents)
            context = orchestrator.execute(context);

            // Persist final results
            persistResults(incident, context);

            log.info("Incident processing completed - IncidentId={}, TraceId={}, Decision={}", 
                incident.getId(), traceId, context.getDecision());

            return context;

        } catch (BaseAgent.AgentExecutionException e) {
            log.error("Incident processing failed - IncidentId={}, TraceId={}: {}", 
                incident.getId(), traceId, e.getMessage(), e);
            
            // Mark incident as failed
            markIncidentAsEscalated(incident, "Pipeline execution failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Process a batch of incidents.
     * Useful for batch operations when multiple incidents need processing.
     * 
     * @param incidents List of incidents
     * @param tenantId Tenant ID
     * @return Number of successfully processed incidents
     */
    public int processBatch(java.util.List<Incident> incidents, String tenantId) {
        int successCount = 0;
        
        for (Incident incident : incidents) {
            try {
                processIncident(incident, tenantId);
                successCount++;
            } catch (Exception e) {
                log.error("Failed to process incident in batch: {}", incident.getId(), e);
            }
        }
        
        log.info("Batch processing completed: {}/{} incidents processed successfully", 
            successCount, incidents.size());
        
        return successCount;
    }

    /**
     * Persist agent context results back to the incident record.
     */
    private void persistResults(Incident incident, AgentContext context) {
        try {
            // Update incident with final decision
            incident.setFinalDecision(context.getDecision());
            incident.setConfidenceScore(context.getFinalConfidenceScore());
            incident.setMatchedSopId(context.getMatchedSopId());
            incident.setMatchedPatternId(context.getMatchedPatternId());
            incident.setPatternSimilarity(context.getPatternSimilarity());

            // Determine final status based on decision
            if ("ESCALATE_TO_HUMAN".equals(context.getDecision())) {
                incident.setStatus("ESCALATED");
            } else if ("AUTO_RESOLVE".equals(context.getDecision())) {
                incident.setStatus("AUTO_RESOLVED");
            } else if ("HITL_REQUIRED".equals(context.getDecision())) {
                incident.setStatus("HITL_PENDING");
            } else {
                incident.setStatus("GUARDRAILS_BLOCKED");
            }

            incident.setResolvedAt(LocalDateTime.now());

            // Save to database
            incidentRepository.save(incident);
            
            log.debug("Persisted incident results - IncidentId={}, Status={}", 
                incident.getId(), incident.getStatus());

        } catch (Exception e) {
            log.error("Failed to persist results for incident {}: {}", incident.getId(), e.getMessage(), e);
            // Don't throw - results are in context even if persistence fails
        }
    }

    /**
     * Mark incident as escalated due to errors or policy violations.
     */
    private void markIncidentAsEscalated(Incident incident, String reason) {
        try {
            incident.setStatus("ESCALATED");
            incident.setFinalDecision("ESCALATE_TO_HUMAN");
            incident.setResolvedAt(LocalDateTime.now());
            incidentRepository.save(incident);
            
            log.info("Marked incident as escalated - IncidentId={}, Reason={}", incident.getId(), reason);
        } catch (Exception e) {
            log.error("Failed to mark incident as escalated: {}", e.getMessage());
        }
    }

    /**
     * Get the orchestrator agent (useful for testing or inspection).
     */
    public OrchestratorAgent getOrchestrator() {
        return orchestrator;
    }
}
