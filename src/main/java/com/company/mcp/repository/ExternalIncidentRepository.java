package com.company.mcp.repository;

import com.company.mcp.model.ExternalIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ExternalIncidentRepository extends JpaRepository<ExternalIncident, UUID>, JpaSpecificationExecutor<ExternalIncident> {
}
