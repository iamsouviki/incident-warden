package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a user-created or AI-generated remediation script stored in the
 * Script Workspace.  Supports the full lifecycle: DRAFT → VALIDATED → EXECUTED.
 */
@Entity
@Table(name = "script_workspace")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "script_content", nullable = false, columnDefinition = "TEXT")
    private String scriptContent;

    /** Script language: "bash" or "powershell". */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String language = "bash";

    /** SOP category for guardrail command-allowlist selection. */
    @Column(nullable = false, length = 50)
    @Builder.Default
    private String category = "APPLICATION";

    @Column(name = "target_host", length = 200)
    private String targetHost;

    /** Lifecycle status: DRAFT, VALIDATED, EXECUTED, FAILED. */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "DRAFT";

    /** JSON result from the last guardrail validation run. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_validation_result", columnDefinition = "JSONB")
    private Map<String, Object> lastValidationResult;

    @Column(name = "last_execution_output", columnDefinition = "TEXT")
    private String lastExecutionOutput;

    @Column(name = "last_execution_exit_code")
    private Integer lastExecutionExitCode;

    @Column(name = "last_executed_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime lastExecutedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

