package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Action execution log - records each tool invocation.
 * Stores pre-state, post-state, and result for rollback capability.
 */
@Entity
@Table(name = "action_execution_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionExecutionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", columnDefinition = "UUID")
    private UUID incidentId;

    @Column(name = "hitl_request_id", columnDefinition = "UUID")
    private UUID hitlRequestId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName; // "sql_execute", "k8s_restart", "dns_update_cname", etc.

    @Column(name = "step_number")
    private Integer stepNumber;

    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters; // Input to the tool

    @Column(name = "result", columnDefinition = "TEXT")
    private String result; // Output from the tool

    @Column(name = "pre_state", columnDefinition = "TEXT")
    private String preState; // State before execution (for rollback)

    @Column(name = "post_state", columnDefinition = "TEXT")
    private String postState; // State after execution

    @Column(length = 20)
    private String status; // SUCCESS, FAILED, ROLLED_BACK, DRY_RUN

    @Column(name = "is_dry_run")
    @Builder.Default
    private Boolean isDryRun = false;

    @Column(name = "executed_by", length = 100)
    private String executedBy; // User or agent name

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "executed_at", columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();
}
