package com.company.mcp.controller;

import com.company.mcp.model.AuditEvent;
import com.company.mcp.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * AuditController — read-only access to the immutable audit_events table.
 *
 * GET /api/v1/audit/tenant/{tenantId}          → all events for tenant
 * GET /api/v1/audit/incident/{incidentId}       → all events for an incident
 * GET /api/v1/audit/type/{eventType}            → filtered by event type
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository auditRepo;

    /** All events for a tenant, newest first. */
    @GetMapping("/tenant/{tenantId}")
    public ResponseEntity<List<AuditEvent>> byTenant(@PathVariable UUID tenantId) {
        try {
            return ResponseEntity.ok(auditRepo.findByTenantIdOrderByCreatedAtDesc(tenantId));
        } catch (Exception e) {
            log.error("Failed to fetch audit events for tenant {}", tenantId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** All events for a specific incident. */
    @GetMapping("/incident/{incidentId}")
    public ResponseEntity<List<AuditEvent>> byIncident(@PathVariable UUID incidentId) {
        try {
            return ResponseEntity.ok(auditRepo.findByIncidentIdOrderByCreatedAtDesc(incidentId));
        } catch (Exception e) {
            log.error("Failed to fetch audit events for incident {}", incidentId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** All events for a specific event type (e.g. HITL_APPROVED, ACTION_EXECUTED). */
    @GetMapping("/type/{eventType}")
    public ResponseEntity<List<AuditEvent>> byType(@PathVariable String eventType) {
        try {
            return ResponseEntity.ok(auditRepo.findByEventTypeOrderByCreatedAtDesc(eventType));
        } catch (Exception e) {
            log.error("Failed to fetch audit events by type {}", eventType, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
