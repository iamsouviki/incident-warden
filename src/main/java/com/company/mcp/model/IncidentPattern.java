package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Incident pattern - learned from historical incidents.
 * Stores embeddings for semantic search via pgvector.
 */
@Entity
@Table(name = "incident_patterns", indexes = {
    @Index(name = "idx_patterns_embedding", columnList = "embedding")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentPattern {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", columnDefinition = "UUID")
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String category;

    @Column(name = "sub_category", length = 100)
    private String subCategory;

    // pgvector embeddings (1536D for text-embedding-3-small)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private String embedding;

    @Column(name = "tag_keywords", columnDefinition = "text[]")
    private String[] tagKeywords;

    @Column(name = "occurrence_count")
    @Builder.Default
    private Integer occurrenceCount = 0;

    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    @Column(name = "failure_count")
    @Builder.Default
    private Integer failureCount = 0;

    @Column(name = "avg_resolution_minutes")
    private Integer avgResolutionMinutes;

    @Column(name = "reliability_score")
    @Builder.Default
    private Double reliabilityScore = 1.0;

    @Column(name = "last_matched_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime lastMatchedAt;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
