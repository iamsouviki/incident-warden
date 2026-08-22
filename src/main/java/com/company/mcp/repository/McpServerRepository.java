package com.company.mcp.repository;

import com.company.mcp.model.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpServerRepository extends JpaRepository<McpServer, UUID> {
    List<McpServer> findByTenantIdOrderByNameAsc(String tenantId);
    Optional<McpServer> findByIdAndTenantId(UUID id, String tenantId);
}
