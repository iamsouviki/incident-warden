package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Standard Operating Procedure (SOP) for incident resolution.
 * Supports versioning, approval workflow, and RAG embeddings.
 */
@Entity
@Table(name = "sop_procedures")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SopProcedure {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", columnDefinition = "UUID")
    private UUID tenantId;

    @Column(length = 20)
    @Builder.Default
    private String scope = "PRIVATE"; // PRIVATE, SHARED, PLATFORM

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 20)
    @Builder.Default
    private String version = "v1.0";

    @Column(length = 100)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    // pgvector embeddings for RAG search
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private String embedding;

    // Stored as JSON for step-by-step execution
    @Column(name = "action_plan_json", columnDefinition = "TEXT")
    private String actionPlanJson; // Steps with preconditions, rollback info

    @Column(name = "preconditions_json", columnDefinition = "TEXT")
    private String preconditionsJson;

    @Column(name = "rollback_steps_json", columnDefinition = "TEXT")
    private String rollbackStepsJson;

    @Column(name = "reliability_score")
    @Builder.Default
    private Double reliabilityScore = 1.0;

    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    @Column(name = "failure_count")
    @Builder.Default
    private Integer failureCount = 0;

    @Column(name = "rejection_count")
    @Builder.Default
    private Integer rejectionCount = 0;

    @Column(name = "owner_team", length = 100)
    private String ownerTeam;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "last_tested_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime lastTestedAt;

    @Column(name = "content_hash", length = 64)
    private String contentHash; // SHA-256 for change detection

    @Column(length = 30)
    @Builder.Default
    private String status = "DRAFT"; // DRAFT, PENDING_APPROVAL, ACTIVE, STALE, ARCHIVED

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
