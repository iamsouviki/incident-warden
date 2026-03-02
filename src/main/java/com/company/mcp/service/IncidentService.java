package com.company.mcp.service;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.agent.AgentPipeline;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Incident Service - Handles incident CRUD operations and processing.
 * Coordinates with the agent pipeline for incident automation.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class IncidentService {
    private final IncidentRepository incidentRepository;
    private final AgentPipeline agentPipeline;
    private final KnowledgeBaseService knowledgeBaseService;

    private static final int DEFAULT_BATCH_SIZE = 5;

    /** Terminal statuses that should trigger archival to the Knowledge Base. */
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of(
            "AUTO_RESOLVED", "HITL_RESOLVED", "ESCALATED", "GUARDRAILS_BLOCKED"
    );

    /**
     * Create a new incident and queue it for processing.
     */
    public Incident createIncident(Incident incident) {
        if (incident.getId() == null) {
            incident.setId(UUID.randomUUID());
        }
        if (incident.getCreatedAt() == null) {
            incident.setCreatedAt(LocalDateTime.now());
        }
        incident.setStatus("PENDING");
        
        log.info("Creating incident: {} from {} ticket {}", 
            incident.getId(), incident.getSourceSystem(), incident.getSourceTicketId());
        
        return incidentRepository.save(incident);
    }

    /**
     * Get an incident by ID.
     */
    public Optional<Incident> getIncidentById(UUID id) {
        return incidentRepository.findById(id);
    }

    /**
     * Process a single incident through the agent pipeline.
     */
    public AgentContext processIncident(UUID incidentId, String tenantId) {
        Optional<Incident> incident = incidentRepository.findById(incidentId);
        
        if (incident.isEmpty()) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }

        log.info("Processing incident: {}", incidentId);
        return agentPipeline.processIncident(incident.get(), tenantId);
    }

    /**
     * Claim next batch of PENDING incidents for processing.
     * This is called by the scheduler to get incidents ready for processing.
     */
    public List<Incident> claimNextBatch(String tenantId) {
        List<Incident> incidents = incidentRepository.claimNextBatch(DEFAULT_BATCH_SIZE, tenantId);
        
        log.info("Claimed {} incidents for processing from tenant {}", incidents.size(), tenantId);
        
        return incidents;
    }

    /**
     * Process a batch of incidents.
     */
    public int processBatch(String tenantId) {
        List<Incident> batch = claimNextBatch(tenantId);
        
        if (batch.isEmpty()) {
            log.debug("No incidents to process for tenant {}", tenantId);
            return 0;
        }

        return agentPipeline.processBatch(batch, tenantId);
    }

    /**
     * Get incidents by status.
     */
    public long countByStatus(String status) {
        return incidentRepository.countByStatus(status);
    }

    /**
     * Get incidents by tenant and status.
     */
    public long countByTenantAndStatus(String tenantId, String status) {
        return incidentRepository.countByTenantIdAndStatus(UUID.fromString(tenantId), status);
    }

    /**
     * Get count of auto-resolved incidents for a tenant.
     */
    public long countAutoResolved(String tenantId) {
        return incidentRepository.countAutoResolvedIncidents(UUID.fromString(tenantId));
    }

    /**
     * Update incident status.
     */
    public Incident updateIncidentStatus(UUID incidentId, String status) {
        return updateIncidentStatus(incidentId, status, null, null, null, null);
    }

    /**
     * Update incident status and, when the status is terminal, automatically
     * archive the incident into the Resolved Incident Knowledge Base.
     *
     * @param incidentId       ID of the incident to update
     * @param status           New status (e.g. AUTO_RESOLVED, HITL_RESOLVED)
     * @param resolutionSummary Optional summary of how it was resolved
     * @param rootCause        Optional root cause description
     * @param resolutionSteps  Optional ordered list of fix actions
     * @param resolvedBy       Operator username or "AUTO"
     */
    public Incident updateIncidentStatus(
            UUID incidentId,
            String status,
            String resolutionSummary,
            String rootCause,
            List<Map<String, Object>> resolutionSteps,
            String resolvedBy
    ) {
        Optional<Incident> incident = incidentRepository.findById(incidentId);

        if (incident.isEmpty()) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }

        Incident updated = incident.get();
        updated.setStatus(status);

        if (TERMINAL_STATUSES.contains(status)) {
            updated.setResolvedAt(LocalDateTime.now());
        }

        log.info("Updated incident {} status to {}", incidentId, status);
        Incident saved = incidentRepository.save(updated);

        // ── Auto-archive to Knowledge Base when incident reaches a terminal state ──
        if (TERMINAL_STATUSES.contains(status)) {
            try {
                knowledgeBaseService.archiveResolved(
                        saved,
                        resolutionSummary,
                        rootCause,
                        resolutionSteps != null ? resolutionSteps : List.of(),
                        List.of(),
                        resolvedBy != null ? resolvedBy : "AUTO"
                );
            } catch (Exception e) {
                // Non-fatal — KB archival should never block the main flow
                log.warn("[KB] Failed to archive incident {} to knowledge base: {}",
                        incidentId, e.getMessage());
            }
        }

        return saved;
    }

    /**
     * Retry processing a failed incident.
     */
    public AgentContext retryIncident(UUID incidentId, String tenantId) {
        Optional<Incident> incident = incidentRepository.findById(incidentId);
        
        if (incident.isEmpty()) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }

        Incident incident_ = incident.get();
        incident_.setRetryCount((incident_.getRetryCount() != null ? incident_.getRetryCount() : 0) + 1);
        incident_.setStatus("PENDING");
        incidentRepository.save(incident_);

        log.info("Retrying incident {} (attempt #{})", incidentId, incident_.getRetryCount());
        
        return agentPipeline.processIncident(incident_, tenantId);
    }
}
