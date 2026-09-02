package com.company.warden.repository;

import com.company.warden.model.ChatSession;
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
    List<ChatSession> findByUsernameAndIsArchivedFalseOrderByUpdatedAtDesc(String username);
    List<ChatSession> findByUsernameAndIsArchivedFalseAndUpdatedAtAfterOrderByUpdatedAtDesc(String username, OffsetDateTime cutoff);
    Optional<ChatSession> findByIdAndUsername(UUID id, String username);

    @Modifying
    @Query("DELETE FROM ChatSession s WHERE s.updatedAt < :cutoff")
    int deleteSessionsOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
