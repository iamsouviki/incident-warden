package com.company.mcp.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "external_incidents", schema = "incident")
public class ExternalIncident {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "assignee")
    private String assignee;

    @Column(name = "assigned_gteam")
    private String assignedGteam;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "status", nullable = false)
    private String status = "New";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    @Column(name = "external_source", nullable = false)
    private String externalSource;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "category")
    private String category = "General";

    @Column(name = "confidence_score")
    private Double confidenceScore = 0.0;

    /**
     * Requester address as supplied by the originating system (ServiceNow caller,
     * FreshService requester). Nullable — an export column that is absent stays absent
     * rather than being guessed at.
     */
    @Column(name = "reporter_email")
    private String reporterEmail;

    public ExternalIncident() {}

    public ExternalIncident(UUID id, String subject, String description, String assignee, String assignedGteam, 
                            String priority, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt, 
                            OffsetDateTime dueDate, String externalSource, String externalId, String category, Double confidenceScore) {
        this.id = id;
        this.subject = subject;
        this.description = description;
        this.assignee = assignee;
        this.assignedGteam = assignedGteam;
        this.priority = priority;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.dueDate = dueDate;
        this.externalSource = externalSource;
        this.externalId = externalId;
        this.category = category;
        this.confidenceScore = confidenceScore;
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }

    public String getAssignedGteam() { return assignedGteam; }
    public void setAssignedGteam(String assignedGteam) { this.assignedGteam = assignedGteam; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public OffsetDateTime getDueDate() { return dueDate; }
    public void setDueDate(OffsetDateTime dueDate) { this.dueDate = dueDate; }

    public String getExternalSource() { return externalSource; }
    public void setExternalSource(String externalSource) { this.externalSource = externalSource; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public String getReporterEmail() { return reporterEmail; }
    public void setReporterEmail(String reporterEmail) { this.reporterEmail = reporterEmail; }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String tenantId;
        private String subject;
        private String description;
        private String assignee;
        private String assignedGteam;
        private String priority;
        private String status = "New";
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private OffsetDateTime dueDate;
        private String externalSource;
        private String externalId;
        private String category = "General";
        private Double confidenceScore = 0.0;
        private String reporterEmail;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder assignee(String assignee) { this.assignee = assignee; return this; }
        public Builder assignedGteam(String assignedGteam) { this.assignedGteam = assignedGteam; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder createdAt(OffsetDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder dueDate(OffsetDateTime dueDate) { this.dueDate = dueDate; return this; }
        public Builder externalSource(String externalSource) { this.externalSource = externalSource; return this; }
        public Builder externalId(String externalId) { this.externalId = externalId; return this; }
        public Builder category(String category) { this.category = category; return this; }
        public Builder confidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; return this; }
        public Builder reporterEmail(String reporterEmail) { this.reporterEmail = reporterEmail; return this; }

        public ExternalIncident build() {
            ExternalIncident incident = new ExternalIncident(id, subject, description, assignee, assignedGteam, priority, status, createdAt, updatedAt, dueDate, externalSource, externalId, category, confidenceScore);
            incident.setTenantId(tenantId);
            incident.setReporterEmail(reporterEmail);
            return incident;
        }
    }
}
