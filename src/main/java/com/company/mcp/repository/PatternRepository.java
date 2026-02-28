package com.company.mcp.repository;

import com.company.mcp.model.IncidentPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Incident pattern repository with vector similarity search.
 */
@Repository
public interface PatternRepository extends JpaRepository<IncidentPattern, UUID> {

    // Vector similarity search using pgvector
    @Query(value = """
        SELECT * FROM incident_patterns
        WHERE tenant_id = CAST(:tenantId AS uuid)
        AND is_active = true
        AND category = :category
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<IncidentPattern> findSimilarPatterns(
        @Param("embedding") String embedding,
        @Param("tenantId") String tenantId,
        @Param("category") String category,
        @Param("limit") int limit
    );

    List<IncidentPattern> findByTenantIdAndCategoryAndIsActiveTrue(UUID tenantId, String category);
    List<IncidentPattern> findByTenantIdAndIsActiveTrueOrderByReliabilityScoreDesc(UUID tenantId);

    Optional<IncidentPattern> findByNameIgnoreCaseAndTenantId(String name, UUID tenantId);
}
