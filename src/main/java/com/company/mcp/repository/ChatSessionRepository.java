package com.company.mcp.repository;

import com.company.mcp.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByTenantIdAndUsernameAndIsArchivedFalseOrderByUpdatedAtDesc(String tenantId, String username);
    Optional<ChatSession> findByIdAndTenantIdAndUsername(UUID id, String tenantId, String username);
    Optional<ChatSession> findByIdAndTenantId(UUID id, String tenantId);
}
