package com.company.mcp.repository;

import com.company.mcp.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByTenantIdAndUsernameAndIsArchivedFalseOrderByUpdatedAtDesc(String tenantId, String username);
    List<ChatSession> findByTenantIdAndUsernameAndIsArchivedFalseAndUpdatedAtAfterOrderByUpdatedAtDesc(String tenantId, String username, OffsetDateTime cutoff);
    Optional<ChatSession> findByIdAndTenantIdAndUsername(UUID id, String tenantId, String username);
    Optional<ChatSession> findByIdAndTenantId(UUID id, String tenantId);

    @Modifying
    @Query("DELETE FROM ChatSession s WHERE s.updatedAt < :cutoff")
    int deleteSessionsOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
