package com.company.mcp.repository;

import com.company.mcp.model.SopProcedure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SOP procedure repository with RAG vector search.
 */
@Repository
public interface SopProcedureRepository extends JpaRepository<SopProcedure, UUID> {

    // RAG search: vector similarity + fuzzy match
    @Query(value = """
        SELECT * FROM sop_procedures
        WHERE tenant_id = CAST(:tenantId AS uuid)
        AND status = 'ACTIVE'
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<SopProcedure> findSimilarSOPs(
        @Param("embedding") String embedding,
        @Param("tenantId") String tenantId,
        @Param("limit") int limit
    );

    List<SopProcedure> findByTenantIdAndCategoryAndStatus(UUID tenantId, String category, String status);
    List<SopProcedure> findByTenantIdAndStatusOrderByVersionDesc(UUID tenantId, String status);
    List<SopProcedure> findByTenantIdAndScopeInAndStatusOrderByUpdatedAtDesc(UUID tenantId, List<String> scopes, String status);

    Optional<SopProcedure> findByTitleIgnoreCaseAndTenantIdAndStatus(String title, UUID tenantId, String status);

    @Query("SELECT s FROM SopProcedure s WHERE s.tenantId = :tenantId AND s.reliabilityScore >= :minReliability ORDER BY s.reliabilityScore DESC")
    List<SopProcedure> findReliableSOPs(@Param("tenantId") UUID tenantId, @Param("minReliability") Double minReliability);
}
