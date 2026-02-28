package com.company.mcp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable audit event log.
 * Append-only with Row-Level Security to prevent tampering.
 * Each event includes SHA-256 hash for tamper-evident recording.
 */
@Entity
@Table(name = "audit_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", columnDefinition = "UUID")
    private UUID incidentId;

    @Column(name = "tenant_id", columnDefinition = "UUID")
    private UUID tenantId;

    @Column(name = "trace_id", length = 64)
    private String traceId; // OpenTelemetry trace ID

    @Column(name = "agent_id", length = 100)
    private String agentId; // Agent or user performing action

    @Column(name = "event_type", length = 100)
    private String eventType; // INCIDENT_CREATED, CONFIDENCE_SCORED, HITL_APPROVED, ACTION_EXECUTED, etc.

    @Column(name = "event_payload", columnDefinition = "TEXT")
    private String eventPayload; // Full event details as JSON string

    @Column(name = "record_hash", length = 64)
    private String recordHash; // SHA-256 hash for tamper detection

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
