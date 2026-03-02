package com.company.mcp.repository;

import com.company.mcp.model.ResolvedIncidentKb;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * Repository for the Resolved Incident Knowledge Base.
 *
 * <p>Supports standard CRUD plus KB-specific queries used by both the
 * REST API and the background embedding-ingestion job.
 */
@Repository
public interface ResolvedIncidentKbRepository extends JpaRepository<ResolvedIncidentKb, UUID> {

    // ── Basic lookups ─────────────────────────────────────────────────────────

    Optional<ResolvedIncidentKb> findByIncidentId(UUID incidentId);

    Optional<ResolvedIncidentKb> findBySourceTicketId(String sourceTicketId);

    List<ResolvedIncidentKb> findByTenantIdOrderByResolvedAtDesc(UUID tenantId);

    Page<ResolvedIncidentKb> findByTenantIdOrderByResolvedAtDesc(UUID tenantId, Pageable pageable);

    // ── RAG embedding queue ───────────────────────────────────────────────────

    /** Returns KB entries that have not yet been ingested into the vector store. */
    @Query("SELECT k FROM ResolvedIncidentKb k WHERE k.embeddingIngested = false ORDER BY k.createdAt ASC")
    List<ResolvedIncidentKb> findPendingEmbedding();

    /** Marks a batch of entries as embedded. */
    @Transactional
    @Modifying
    @Query("UPDATE ResolvedIncidentKb k SET k.embeddingIngested = true WHERE k.id IN :ids")
    void markEmbeddingIngested(@Param("ids") List<UUID> ids);

    // ── Filtering ─────────────────────────────────────────────────────────────

    List<ResolvedIncidentKb> findByTenantIdAndCategoryOrderByResolvedAtDesc(UUID tenantId, String category);

    List<ResolvedIncidentKb> findByTenantIdAndSeverityOrderByResolvedAtDesc(UUID tenantId, String severity);

    @Query("""
        SELECT k FROM ResolvedIncidentKb k
        WHERE k.tenantId = :tenantId
          AND (:category IS NULL OR k.category = :category)
          AND (:severity IS NULL OR k.severity = :severity)
        ORDER BY k.resolvedAt DESC
        """)
    Page<ResolvedIncidentKb> search(
            @Param("tenantId")  UUID     tenantId,
            @Param("category")  String   category,
            @Param("severity")  String   severity,
            Pageable pageable
    );

    // ── Text search (simple ILIKE — fallback when pgvector is unavailable) ────
    @Query("""
        SELECT k FROM ResolvedIncidentKb k
        WHERE k.tenantId = :tenantId
          AND (LOWER(k.title)              LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(k.description)        LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(k.resolutionSummary)  LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(k.rootCause)          LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY k.resolvedAt DESC
        """)
    List<ResolvedIncidentKb> fullTextSearch(@Param("tenantId") UUID tenantId, @Param("q") String query);

    // ── Statistics ────────────────────────────────────────────────────────────

    long countByTenantId(UUID tenantId);

    @Query("SELECT COUNT(k) FROM ResolvedIncidentKb k WHERE k.tenantId = :tenantId AND k.category = :category")
    long countByTenantIdAndCategory(@Param("tenantId") UUID tenantId, @Param("category") String category);
}
