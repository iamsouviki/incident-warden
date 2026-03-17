package com.company.mcp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "approved_remediation_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovedRemediationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "UUID")
    private UUID tenantId;

    @Column(name = "incident_id", columnDefinition = "UUID")
    private UUID incidentId;

    @Column(name = "proposal_id", columnDefinition = "UUID")
    private UUID proposalId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "service_name", length = 120)
    private String serviceName;

    @Column(name = "environment_name", nullable = false, length = 80)
    @Builder.Default
    private String environmentName = "default";

    @Column(name = "incident_fingerprint", length = 255)
    private String incidentFingerprint;

    @Column(name = "shell_type", nullable = false, length = 20)
    @Builder.Default
    private String shellType = "bash";

    @Column(name = "action_class", nullable = false, length = 50)
    @Builder.Default
    private String actionClass = "manual_review";

    @Column(name = "risk_level", nullable = false, length = 20)
    @Builder.Default
    private String riskLevel = "MEDIUM";

    @Column(name = "auto_eligible", nullable = false)
    @Builder.Default
    private Boolean autoEligible = false;

    @Column(name = "data_manipulation", nullable = false)
    @Builder.Default
    private Boolean dataManipulation = false;

    @Column(name = "embedding_ingested", nullable = false)
    @Builder.Default
    private Boolean embeddingIngested = false;

    @Column(name = "script_content", nullable = false, columnDefinition = "TEXT")
    private String scriptContent;

    @Column(name = "script_hash", length = 128)
    private String scriptHash;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "rollback_plan", columnDefinition = "TEXT")
    private String rollbackPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_plan_json", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private List<String> validationPlanJson = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private Map<String, Object> metadataJson = Map.of();

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private Integer successCount = 1;

    @Column(name = "failure_count", nullable = false)
    @Builder.Default
    private Integer failureCount = 0;

    @Column(name = "last_used_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }
}
