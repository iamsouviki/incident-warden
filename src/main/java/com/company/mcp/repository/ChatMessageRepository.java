package com.company.mcp.repository;

import com.company.mcp.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
    void deleteBySessionId(UUID sessionId);

    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.sessionId NOT IN (SELECT s.id FROM ChatSession s)")
    int deleteOrphanedMessages();
}
