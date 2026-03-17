package com.company.mcp.repository;

import com.company.mcp.model.IncidentConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentConversationMessageRepository extends JpaRepository<IncidentConversationMessage, UUID> {

    List<IncidentConversationMessage> findByThreadIdOrderByCreatedAtAsc(UUID threadId);
}
