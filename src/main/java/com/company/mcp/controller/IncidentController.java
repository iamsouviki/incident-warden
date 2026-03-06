package com.company.mcp.controller;

import com.company.mcp.agent.AgentContext;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.service.IncidentService;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Incident Management API - Phase 8 REST endpoints.
 * Handles incident creation, retrieval, and HITL approval workflow.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {
    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;

    /**
     * Create a new incident and queue it for processing.
     */
    @PostMapping
    public ResponseEntity<?> createIncident(@RequestBody Incident incident) {
        try {
            Incident created = incidentService.createIncident(incident);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            log.error("Failed to create incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Get incident by ID.
     */
    @GetMapping("/{incidentId}")
    public ResponseEntity<?> getIncident(@PathVariable UUID incidentId) {
        try {
            Optional<Incident> incident = incidentService.getIncidentById(incidentId);
            return incident.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to get incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Process a single incident through the agent pipeline.
     */
    @PostMapping("/{incidentId}/process")
    public ResponseEntity<?> processIncident(
            @PathVariable UUID incidentId,
            @RequestParam String tenantId) {
        try {
            AgentContext context = incidentService.processIncident(incidentId, tenantId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("incidentId", incidentId);
            response.put("decision", context.getDecision());
            response.put("confidenceScore", context.getFinalConfidenceScore());
            response.put("status", context.getIncident().getStatus());
            response.put("traceId", context.getTraceId());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to process incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Retry processing a failed incident.
     */
    @PostMapping("/{incidentId}/retry")
    public ResponseEntity<?> retryIncident(
            @PathVariable UUID incidentId,
            @RequestParam String tenantId) {
        try {
            AgentContext context = incidentService.retryIncident(incidentId, tenantId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("incidentId", incidentId);
            response.put("decision", context.getDecision());
            response.put("confidenceScore", context.getFinalConfidenceScore());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to retry incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * List incidents for a tenant.
     */
    @GetMapping
    public ResponseEntity<?> listIncidents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<Incident> incidents;
            if (tenantId != null) {
                UUID tenantUuid = UUID.fromString(tenantId);
                if (status != null) {
                    incidents = incidentRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantUuid, status);
                } else {
                    incidents = incidentRepository.findByTenantIdOrderByCreatedAtDesc(tenantUuid);
                }
                if (incidents.size() > limit) {
                    incidents = incidents.subList(0, limit);
                }
            } else {
                incidents = java.util.Collections.emptyList();
            }
            return ResponseEntity.ok(incidents);
        } catch (Exception e) {
            log.error("Failed to list incidents", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Get incident statistics for a tenant.
     */
    @GetMapping("/stats/{tenantId}")
    public ResponseEntity<?> getStats(@PathVariable String tenantId) {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalPending", incidentService.countByTenantAndStatus(tenantId, "PENDING"));
            stats.put("processing", incidentService.countByTenantAndStatus(tenantId, "PROCESSING"));
            stats.put("autoResolved", incidentService.countAutoResolved(tenantId));
            stats.put("hitlPending", incidentService.countByTenantAndStatus(tenantId, "HITL_PENDING"));
            stats.put("escalated", incidentService.countByTenantAndStatus(tenantId, "ESCALATED"));

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Failed to get stats", e);
            return ApiErrorResponses.badRequest();
        }
    }
}
