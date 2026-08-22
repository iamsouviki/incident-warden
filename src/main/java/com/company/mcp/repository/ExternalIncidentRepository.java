package com.company.mcp.repository;

import com.company.mcp.model.ExternalIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExternalIncidentRepository extends JpaRepository<ExternalIncident, UUID>, JpaSpecificationExecutor<ExternalIncident> {

    /** Tenant-scoped and bounded: assistant context must never span tenants or grow without limit. */
    List<ExternalIncident> findTop50ByTenantIdOrderByUpdatedAtDesc(String tenantId);

    /** @see IncidentRepository#findMaxInternalTicketNumber() — ticket numbers are shared across both tables. */
    @Query("select max(cast(substring(i.externalId, 4) as long)) from ExternalIncident i "
            + "where i.externalId like 'INC%' and length(i.externalId) = 12")
    Long findMaxInternalTicketNumber();
}
