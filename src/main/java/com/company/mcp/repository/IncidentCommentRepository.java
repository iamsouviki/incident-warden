package com.company.mcp.repository;

import com.company.mcp.model.IncidentComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentCommentRepository extends JpaRepository<IncidentComment, UUID> {
    List<IncidentComment> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
