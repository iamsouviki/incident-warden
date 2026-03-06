package com.company.mcp.repository;

import com.company.mcp.model.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Incident repository with job queue semantics.
 * claimNextBatch() uses SKIP LOCKED for safe concurrent processing.
 */
@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    // Job queue: claim next batch atomically
    @Transactional
    @Modifying
    @Query(value = """
        WITH claimed AS (
            SELECT id FROM incidents
            WHERE status = 'PENDING'
            AND (tenant_id = CAST(:tenantId AS uuid) OR CAST(:tenantId AS text) IS NULL)
            ORDER BY
                CASE severity WHEN 'P1' THEN 1 WHEN 'P2' THEN 2
                              WHEN 'P3' THEN 3 ELSE 4 END ASC,
                created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE incidents
        SET status = 'PROCESSING',
            processing_started_at = now()
        WHERE id IN (SELECT id FROM claimed)
        RETURNING *
        """, nativeQuery = true)
    List<Incident> claimNextBatch(
        @Param("batchSize") int batchSize,
        @Param("tenantId") String tenantId
    );

    // Statistics
    Integer countByStatus(String status);
    Integer countByTenantIdAndStatus(UUID tenantId, String status);
    Integer countByTenantIdAndStatusAndSeverity(UUID tenantId, String status, String severity);

    Optional<Incident> findBySourceSystemAndSourceTicketId(String sourceSystem, String sourceTicketId);

    List<Incident> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);

    List<Incident> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Transactional
    @Modifying
    @Query("UPDATE Incident i SET i.matchedSopId = null WHERE i.matchedSopId = :sopId")
    int clearMatchedSop(@Param("sopId") UUID sopId);

    @Query("SELECT COUNT(i) FROM Incident i WHERE i.tenantId = :tenantId AND i.finalDecision = 'AUTO_RESOLVE'")
    Long countAutoResolvedIncidents(@Param("tenantId") UUID tenantId);
}
