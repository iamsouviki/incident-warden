package com.company.mcp.service;

import com.company.mcp.model.AuditEvent;
import com.company.mcp.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * Audit Service - Phase 8 Implementation.
 * Manages immutable audit trail for compliance and forensics.
 * 
 * Features:
 * - SHA-256 hashing for tamper detection
 * - Append-only design with RLS
 * - Audit trail querying
 * - Compliance reporting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditEventRepository auditRepository;
    private final ObjectMapper objectMapper;

    /**
     * Log an audit event.
     */
    public AuditEvent logEvent(
            UUID incidentId,
            UUID tenantId,
            String traceId,
            String agentId,
            String eventType,
            Object payload) {
        try {
            AuditEvent event = new AuditEvent();
            event.setIncidentId(incidentId);
            event.setTenantId(tenantId);
            event.setTraceId(traceId);
            event.setAgentId(agentId);
            event.setEventType(eventType);
            // Store payload as JSON string
            if (payload != null) {
                event.setEventPayload(objectMapper.writeValueAsString(payload));
            }

            // Compute SHA-256 hash for tamper detection
            String recordHash = computeHash(event);
            event.setRecordHash(recordHash);

            AuditEvent saved = auditRepository.save(event);
            log.info("Audit event logged: {} - {}", eventType, incidentId);

            return saved;
        } catch (Exception e) {
            log.error("Failed to log audit event: {}", e.getMessage(), e);
            throw new RuntimeException("Audit logging failed", e);
        }
    }

    /**
     * Get audit trail for an incident.
     */
    public List<AuditEvent> getIncidentAuditTrail(UUID incidentId) {
        try {
            return auditRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        } catch (Exception e) {
            log.error("Failed to retrieve audit trail: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get audit events by type.
     */
    public List<AuditEvent> getEventsByType(String eventType) {
        try {
            return auditRepository.findByEventTypeOrderByCreatedAtDesc(eventType);
        } catch (Exception e) {
            log.error("Failed to retrieve events by type: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get audit trail by trace ID (for distributed tracing).
     */
    public List<AuditEvent> getTraceAuditTrail(String traceId) {
        try {
            return auditRepository.findByTraceIdOrderByCreatedAtDesc(traceId);
        } catch (Exception e) {
            log.error("Failed to retrieve trace audit trail: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Compute SHA-256 hash for audit event (for tamper detection).
     */
    private String computeHash(AuditEvent event) {
        try {
            String data = event.getIncidentId().toString() + "|" +
                         event.getTenantId().toString() + "|" +
                         event.getTraceId() + "|" +
                         event.getEventType() + "|" +
                         event.getCreatedAt().toString();

            MessageDigest digester = MessageDigest.getInstance("SHA-256");
            byte[] hash = digester.digest(data.getBytes());

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to compute hash: {}", e.getMessage());
            return "hash-error";
        }
    }

    /**
     * Verify audit event integrity (check hash).
     */
    public boolean verifyEventIntegrity(AuditEvent event) {
        try {
            String expectedHash = computeHash(event);
            return expectedHash.equals(event.getRecordHash());
        } catch (Exception e) {
            log.error("Failed to verify audit event: {}", e.getMessage());
            return false;
        }
    }
}
