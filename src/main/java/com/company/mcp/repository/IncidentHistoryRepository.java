package com.company.mcp.repository;

import com.company.mcp.model.IncidentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentHistoryRepository extends JpaRepository<IncidentHistory, UUID> {
    List<IncidentHistory> findByIncidentIdOrderByUpdatedAtDesc(UUID incidentId);
}
