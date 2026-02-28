package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Human-in-the-loop (HITL) approval request.
 * Created for incidents with confidence score 80-99%.
 */
@Entity
@Table(name = "hitl_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HitlRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", columnDefinition = "UUID", nullable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", columnDefinition = "UUID")
    private UUID tenantId;

    @Column(name = "confidence_log_id", columnDefinition = "UUID")
    private UUID confidenceLogId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED, MODIFIED, TIMED_OUT, ESCALATED

    // Payload with incident details and recommended SOP
    @Column(name = "approval_payload", columnDefinition = "TEXT", nullable = false)
    private String approvalPayload;

    @Column(length = 20)
    private String decision; // APPROVED, REJECTED, MODIFIED

    @Column(name = "decided_by", length = 100)
    private String decidedBy; // Email of approver

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    // Modifications made by human
    @Column(name = "modifications", columnDefinition = "TEXT")
    private String modifications;

    @Column(name = "outcome", length = 30)
    private String outcome; // SUCCESS, FAILED, ROLLED_BACK

    @Column(name = "expires_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "decided_at", columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
