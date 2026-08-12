package com.company.mcp.repository;

import com.company.mcp.model.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {
    Optional<Incident> findFirstByExternalSourceAndExternalId(String externalSource, String externalId);
    Optional<Incident> findFirstByTenantIdAndExternalSourceAndExternalId(String tenantId, String externalSource, String externalId);
}
