package com.company.mcp.repository;

import com.company.mcp.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    Optional<AuditEvent> findFirstByTenantIdOrderByCreatedAtDesc(String tenantId);
}
