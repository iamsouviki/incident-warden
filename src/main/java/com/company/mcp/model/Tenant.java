package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Multi-tenant organization configuration.
 * Stores thresholds and policies for auto-resolution, HITL, risk assessment, etc.
 */
@Entity
@Table(name = "tenants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    @Builder.Default
    private String plan = "STANDARD";

    // Confidence thresholds
    @Column(name = "auto_resolve_threshold")
    @Builder.Default
    private Double autoResolveThreshold = 1.0; // 100% confidence required

    @Column(name = "hitl_threshold")
    @Builder.Default
    private Double hitlThreshold = 0.8; // 80% confidence for HITL

    // P1 safety
    @Column(name = "allow_p1_auto_resolve")
    @Builder.Default
    private Boolean allowP1AutoResolve = false;

    // Risk limits
    @Column(name = "max_blast_radius_pct")
    @Builder.Default
    private Integer maxBlastRadiusPct = 40; // % of users affected

    // HITL timeout windows
    @Column(name = "hitl_timeout_p1_min")
    @Builder.Default
    private Integer hitlTimeoutP1Min = 15; // minutes

    @Column(name = "hitl_timeout_p2_min")
    @Builder.Default
    private Integer hitlTimeoutP2Min = 30; // minutes

    // SOP policies
    @Column(name = "can_use_shared_sops")
    @Builder.Default
    private Boolean canUseSharedSops = false;

    @Column(name = "can_publish_sops")
    @Builder.Default
    private Boolean canPublishSops = false;

    // Quota limits
    @Column(name = "max_monthly_incidents")
    @Builder.Default
    private Integer maxMonthlyIncidents = 5000;

    @Column(name = "max_sops")
    @Builder.Default
    private Integer maxSops = 100;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
