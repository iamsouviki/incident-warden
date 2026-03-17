package com.company.mcp.repository;

import com.company.mcp.model.IncidentConversationThread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentConversationThreadRepository extends JpaRepository<IncidentConversationThread, UUID> {

    List<IncidentConversationThread> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
