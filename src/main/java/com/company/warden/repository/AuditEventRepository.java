package com.company.warden.repository;

import com.company.warden.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    Optional<AuditEvent> findFirstByOrderByCreatedAtDesc();
}
