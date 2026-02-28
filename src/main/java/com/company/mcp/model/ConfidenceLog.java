package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Confidence scoring breakdown for transparency.
 * Records all factors contributing to the final score.
 */
@Entity
@Table(name = "confidence_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfidenceLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", columnDefinition = "UUID")
    private UUID incidentId;

    @Column(name = "pattern_id", columnDefinition = "UUID")
    private UUID patternId;

    @Column(name = "sop_id", columnDefinition = "UUID")
    private UUID sopId;

    // Weighted component scores
    @Column(name = "score_pattern_sim")
    private Double scorePatternSim; // 35% weight

    @Column(name = "score_historical")
    private Double scoreHistorical; // 25% weight

    @Column(name = "score_sop_reliability")
    private Double scoreSopReliability; // 20% weight

    @Column(name = "score_system_health")
    private Double scoreSystemHealth; // 15% weight

    @Column(name = "penalty_risk_factor")
    private Double penaltyRiskFactor; // Deducted from composite

    // Final result
    @Column(name = "final_score")
    private Double finalScore;

    @Column(name = "hard_override_applied")
    @Builder.Default
    private Boolean hardOverrideApplied = false;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(length = 30)
    private String decision; // AUTO_RESOLVE, HITL_REQUIRED, ESCALATE_TO_HUMAN

    @Column(name = "reasoning_text", columnDefinition = "TEXT")
    private String reasoningText; // Human-readable explanation

    @Column(name = "computed_at", columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private LocalDateTime computedAt = LocalDateTime.now();
}
