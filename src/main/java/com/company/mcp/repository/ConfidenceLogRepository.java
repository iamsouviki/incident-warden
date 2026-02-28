package com.company.mcp.repository;

import com.company.mcp.model.ConfidenceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfidenceLogRepository extends JpaRepository<ConfidenceLog, UUID> {
    Optional<ConfidenceLog> findByIncidentId(UUID incidentId);
    List<ConfidenceLog> findByIncidentIdOrderByComputedAtDesc(UUID incidentId);
    List<ConfidenceLog> findByDecision(String decision);
    Integer countByDecision(String decision);
}
