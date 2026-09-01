package com.company.mcp.repository;

import com.company.mcp.model.ActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, UUID> {
    long countByPlanId(UUID planId);

    List<ActionExecution> findByPlanIdOrderByStartedAtAsc(UUID planId);

    List<ActionExecution> findByTenantIdOrderByStartedAtDesc(String tenantId);

    /**
     * Remediations that actually succeeded under a human approval, newest first.
     */
    List<ActionExecution> findTop100ByTenantIdAndStatusAndHitlRequestIdIsNotNullOrderByCompletedAtDesc(
            String tenantId, String status);
}
