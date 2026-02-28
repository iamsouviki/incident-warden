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

    private static final int DEFAULT_BATCH_SIZE = 5;

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
        Optional<Incident> incident = incidentRepository.findById(incidentId);
        
        if (incident.isEmpty()) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }

        Incident updated = incident.get();
        updated.setStatus(status);
        
        if ("AUTO_RESOLVED".equals(status) || "ESCALATED".equals(status) || 
            "GUARDRAILS_BLOCKED".equals(status)) {
            updated.setResolvedAt(LocalDateTime.now());
        }

        log.info("Updated incident {} status to {}", incidentId, status);
        
        return incidentRepository.save(updated);
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
