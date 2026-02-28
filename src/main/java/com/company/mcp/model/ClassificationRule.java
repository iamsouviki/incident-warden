package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Classification rule for incident categorization.
 * Regex patterns used by classifier agent for rule-based classification.
 */
@Entity
@Table(name = "classification_rules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationRule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", columnDefinition = "UUID", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 500)
    private String pattern; // Regex pattern

    @Column(nullable = false, length = 100)
    private String category;

    @Column(name = "sub_category", length = 100)
    private String subCategory;

    @Column(length = 5)
    private String severity; // P1, P2, P3, P4

    @Column()
    private Double confidence;

    @Column()
    @Builder.Default
    private Integer priority = 100;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
