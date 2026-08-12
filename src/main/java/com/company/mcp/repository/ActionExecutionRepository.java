package com.company.mcp.repository;

import com.company.mcp.model.ActionExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, UUID> {
    long countByPlanId(UUID planId);
}
