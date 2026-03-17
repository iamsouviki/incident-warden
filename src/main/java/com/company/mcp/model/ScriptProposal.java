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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "script_proposals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "thread_id", nullable = false, columnDefinition = "UUID")
    private UUID threadId;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "UUID")
    private UUID tenantId;

    @Column(name = "incident_id", columnDefinition = "UUID")
    private UUID incidentId;

    @Column(name = "attempt_no", nullable = false)
    @Builder.Default
    private Integer attemptNo = 1;

    @Column(name = "shell_type", nullable = false, length = 20)
    @Builder.Default
    private String shellType = "bash";

    @Column(name = "target_ref", length = 255)
    private String targetRef;

    @Column(name = "script_content", nullable = false, columnDefinition = "TEXT")
    private String scriptContent;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "risk_level", nullable = false, length = 20)
    @Builder.Default
    private String riskLevel = "MEDIUM";

    @Column(name = "approval_required", nullable = false)
    @Builder.Default
    private Boolean approvalRequired = true;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "rollback_plan", columnDefinition = "TEXT")
    private String rollbackPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_plan_json", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private List<String> validationPlanJson = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "JSONB", nullable = false)
    @Builder.Default
    private Map<String, Object> evidenceJson = Map.of();

    @Column(name = "script_hash", length = 128)
    private String scriptHash;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime approvedAt;

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
        if (scriptContent != null && !scriptContent.isBlank()) {
            scriptHash = sha256(scriptContent);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash script content", e);
        }
    }
}
