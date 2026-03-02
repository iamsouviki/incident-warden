package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolved Incident Knowledge Base entry.
 *
 * <p>Every incident that reaches a terminal state (AUTO_RESOLVED, HITL_RESOLVED,
 * ESCALATED, etc.) is archived here together with its full resolution details and
 * operator comments.  Rows are also ingested into the pgvector VectorStore so the
 * RAG pipeline can suggest solutions for <em>new</em> incidents by finding similar
 * past resolutions.
 *
 * <h3>RAG flow</h3>
 * <ol>
 *   <li>Incident resolves → {@link com.company.mcp.service.KnowledgeBaseService#archiveResolved}
 *       creates a row with {@code embedding_ingested = false}.</li>
 *   <li>A background task picks up un-ingested rows and calls
 *       {@link com.company.mcp.service.RagService#ingestResolvedIncident}.</li>
 *   <li>When a new incident arrives, {@link com.company.mcp.service.RagService#findSimilarResolved}
 *       retrieves the top-K most relevant past resolutions to include in the LLM prompt.</li>
 * </ol>
 */
@Entity
@Table(
    name = "resolved_incident_kb",
    indexes = {
        @Index(name = "idx_kb_tenant_id",   columnList = "tenant_id"),
        @Index(name = "idx_kb_category",    columnList = "category"),
        @Index(name = "idx_kb_severity",    columnList = "severity"),
        @Index(name = "idx_kb_resolved_at", columnList = "resolved_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedIncidentKb {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Original incident linkage ─────────────────────────────────────────────
    /** UUID of the original incident row (nullable — may be purged). */
    @Column(name = "incident_id", columnDefinition = "UUID")
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "UUID")
    private UUID tenantId;

    @Column(name = "source_system", nullable = false, length = 50)
    private String sourceSystem;

    @Column(name = "source_ticket_id", length = 100)
    private String sourceTicketId;

    // ── Incident description ──────────────────────────────────────────────────
    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String category;

    @Column(name = "sub_category", length = 100)
    private String subCategory;

    /** Severity: P1, P2, P3, or P4. */
    @Column(nullable = false, length = 5)
    private String severity;

    @Column(name = "affected_systems", columnDefinition = "text[]")
    private String[] affectedSystems;

    // ── Resolution knowledge ──────────────────────────────────────────────────
    /** Brief summary of how the incident was resolved. */
    @Column(name = "resolution_summary", columnDefinition = "TEXT")
    private String resolutionSummary;

    /** Identified root cause. */
    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    /**
     * Ordered list of resolution steps taken.
     * Each element: {@code { "step": 1, "action": "...", "tool": "...", "result": "..." }}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolution_steps", columnDefinition = "JSONB")
    @Builder.Default
    private List<Map<String, Object>> resolutionSteps = List.of();

    // ── Operator comments ─────────────────────────────────────────────────────
    /**
     * Array of operator / HITL comments left during incident handling.
     * Each element: {@code { "author": "...", "role": "...", "text": "...", "ts": "ISO-DATETIME" }}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comments", columnDefinition = "JSONB")
    @Builder.Default
    private List<Map<String, Object>> comments = List.of();

    // ── Classification metadata ───────────────────────────────────────────────
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    /** "AUTO" for automated resolution or operator username for HITL. */
    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    /** Terminal status of the original incident (AUTO_RESOLVED, HITL_RESOLVED, ESCALATED, …). */
    @Column(name = "original_status", length = 40)
    private String originalStatus;

    /** Model confidence score at the time of resolution. */
    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "matched_sop_id", columnDefinition = "UUID")
    private UUID matchedSopId;

    @Column(name = "matched_sop_title", length = 255)
    private String matchedSopTitle;

    // ── RAG / embedding tracking ──────────────────────────────────────────────
    /**
     * Set to {@code true} once this KB entry has been successfully ingested
     * into the pgvector VectorStore and is therefore searchable via RAG.
     */
    @Column(name = "embedding_ingested", nullable = false)
    @Builder.Default
    private Boolean embeddingIngested = false;

    // ── Timestamps ────────────────────────────────────────────────────────────
    @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
