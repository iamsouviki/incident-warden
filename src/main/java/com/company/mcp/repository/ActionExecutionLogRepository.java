package com.company.mcp.repository;

import com.company.mcp.model.ActionExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActionExecutionLogRepository extends JpaRepository<ActionExecutionLog, UUID> {
    List<ActionExecutionLog> findByIncidentIdOrderByStepNumber(UUID incidentId);
    List<ActionExecutionLog> findByHitlRequestIdOrderByExecutedAt(UUID hitlRequestId);
    List<ActionExecutionLog> findByToolName(String toolName);
    Integer countByStatus(String status);
}
