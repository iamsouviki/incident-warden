package com.company.mcp.repository;

import com.company.mcp.model.ScriptWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Script Workspace CRUD operations.
 */
@Repository
public interface ScriptWorkspaceRepository extends JpaRepository<ScriptWorkspace, UUID> {

    List<ScriptWorkspace> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

    List<ScriptWorkspace> findByTenantIdAndStatus(UUID tenantId, String status);

    List<ScriptWorkspace> findByCreatedBy(String createdBy);

    List<ScriptWorkspace> findByCategory(String category);

    List<ScriptWorkspace> findBySopId(UUID sopId);

    List<ScriptWorkspace> findAllByOrderByUpdatedAtDesc();
}
