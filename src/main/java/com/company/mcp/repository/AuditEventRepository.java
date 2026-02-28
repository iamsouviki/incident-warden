package com.company.mcp.repository;

import com.company.mcp.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Audit event repository - append-only with RLS.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
    List<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<AuditEvent> findByEventTypeOrderByCreatedAtDesc(String eventType);
    List<AuditEvent> findByTraceIdOrderByCreatedAtDesc(String traceId);
}
