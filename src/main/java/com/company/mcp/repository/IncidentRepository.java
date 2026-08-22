package com.company.mcp.repository;

import com.company.mcp.model.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {
    Optional<Incident> findFirstByExternalSourceAndExternalId(String externalSource, String externalId);
    Optional<Incident> findFirstByTenantIdAndExternalSourceAndExternalId(String tenantId, String externalSource, String externalId);

    /** Tenant-scoped and bounded: assistant context must never span tenants or grow without limit. */
    List<Incident> findTop50ByTenantIdOrderByUpdatedAtDesc(String tenantId);

    /**
     * Highest internally-issued ticket number, or null when none has been issued.
     * Deliberately not tenant-scoped: {@code external_id} is unique across the table, so
     * the next number has to clear every tenant's, not just the caller's.
     */
    @Query("select max(cast(substring(i.externalId, 4) as long)) from Incident i "
            + "where i.externalId like 'INC%' and length(i.externalId) = 12")
    Long findMaxInternalTicketNumber();
}
