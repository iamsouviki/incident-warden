package com.company.mcp.model;

import com.company.mcp.domain.Decision;
import com.company.mcp.domain.IncidentStatus;
import com.company.mcp.domain.Severity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core incident entity. Acts as both incident record and job queue item.
 * Processing pipeline claims incidents atomically with SKIP LOCKED.
 */
@Entity
@Table(name = "incidents", indexes = {
    @Index(name = "idx_incidents_queue", columnList = "tenant_id,severity,created_at"),
    @Index(name = "idx_incidents_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Incident {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", columnDefinition = "UUID")
    private UUID tenantId;

    @Column(name = "source_system", nullable = false, length = 50)
    private String sourceSystem;

    @Column(name = "source_ticket_id", nullable = false, length = 100, unique = true)
    private String sourceTicketId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String category;

    @Column(name = "sub_category", length = 100)
    private String subCategory;

    @Column(nullable = false, length = 5)
    private String severity; // P1, P2, P3, P4

    @Column(name = "affected_systems", columnDefinition = "text[]")
    private String[] affectedSystems;

    @Column(nullable = false, length = 40)
    private String status; // PENDING, PROCESSING, AUTO_RESOLVED, HITL_PENDING, etc.

    @Column(name = "final_decision", length = 20)
    private String finalDecision; // AUTO_RESOLVE, HITL_REQUIRED, ESCALATE

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "processing_started_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime processingStartedAt;

    @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime resolvedAt;

    @Column(name = "matched_sop_id", columnDefinition = "UUID")
    private UUID matchedSopId;

    @Column(name = "matched_pattern_id", columnDefinition = "UUID")
    private UUID matchedPatternId;

    @Column(name = "pattern_similarity")
    private Double patternSimilarity;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
