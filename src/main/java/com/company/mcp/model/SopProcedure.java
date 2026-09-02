package com.company.mcp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An approved remediation procedure.
 *
 * This is the record the HITL gate treats as authority to act. Three things depend on
 * it, which is why it is one table and not three:
 *   - SOP evidence: only an APPROVED row can back a plan.
 *   - The executor's registry: {@code actionKey} names the tool and its parameters.
 *   - The learning loop: {@code successCount}/{@code failureCount} feed confidence.
 *
 * Mapped with plain JPA (no native SQL) so the same code runs on Postgres and on the
 * H2 local profile. The pgvector path stays for semantic search over SOP documents;
 * this table is the authorisation record, which must be exact rather than approximate.
 */
@Entity
@Table(name = "sop_procedure", schema = "sop")
public class SopProcedure {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Groups the steps of one procedure together. */
    @Column(name = "sop_id", nullable = false, length = 64)
    private String sopId;

    @Column(name = "step_number", nullable = false)
    private int stepNumber = 1;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    /** Space-separated terms matched against incident text. */
    @Column(name = "match_keywords", length = 1000)
    private String matchKeywords;

    /** Tool invocation, e.g. {@code RESTART_SERVICE:tomcat:linux} or {@code CHECK_URL:http://host/health:200}. */
    @Column(name = "action_key", nullable = false, length = 500)
    private String actionKey;

    /** DRAFT | APPROVED | RETIRED. Only APPROVED may back a remediation plan. */
    @Column(name = "approval_status", nullable = false, length = 32)
    private String approvalStatus = "DRAFT";

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval = true;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder = 10;

    /** Confidence in this procedure, in [0,1]. Recomputed from the counters below. */
    @Column(name = "reliability", nullable = false)
    private double reliability = 0.70;

    @Column(name = "success_count", nullable = false)
    private int successCount = 0;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    @Column(name = "approved_by", length = 150)
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public SopProcedure() {}

    /**
     * Observed success rate, with the configured reliability acting as a prior so a
     * single early failure does not drive confidence to zero.
     */
    public double observedSuccessRate() {
        int total = successCount + failureCount;
        if (total == 0) return reliability;
        return (double) successCount / total;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSopId() { return sopId; }
    public void setSopId(String sopId) { this.sopId = sopId; }
    public int getStepNumber() { return stepNumber; }
    public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMatchKeywords() { return matchKeywords; }
    public void setMatchKeywords(String matchKeywords) { this.matchKeywords = matchKeywords; }
    public String getActionKey() { return actionKey; }
    public void setActionKey(String actionKey) { this.actionKey = actionKey; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }
    public int getExecutionOrder() { return executionOrder; }
    public void setExecutionOrder(int executionOrder) { this.executionOrder = executionOrder; }
    public double getReliability() { return reliability; }
    public void setReliability(double reliability) { this.reliability = reliability; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
