package com.company.warden.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "incidents", schema = "incident")
public class Incident {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

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

    // No field default. This class is also the request body every PUT deserializes into, so
    // an initialiser here is indistinguishable from a value the caller sent: `status = "New"`
    // meant a PUT of {"targetHost": ...} silently reverted a status the remediation lane had
    // just set. Creation sets it explicitly (routeIncident, convertToIncident, the builder).
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    @Column(name = "external_source")
    private String externalSource = "Internal";

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "category")
    private String category = "General";

    /**
     * Who to tell when this incident is acted on automatically. Nullable: incidents
     * predating this column, and sources that supply no requester address, have none —
     * NotificationService skips a recipient it does not have rather than inventing one.
     * Distinct from {@code assignee}, which is an operator name, not a deliverable address.
     */
    @Column(name = "reporter_email")
    private String reporterEmail;

    /**
     * The store this incident belongs to, e.g. {@code 0042}.
     *
     * A permission boundary, not a label: autonomy is inherited per store. A tool proven
     * at store 0042 does not authorise itself at store 0099, however similar the wording
     * of the two tickets is. Nullable — a non-store incident keeps the old behaviour.
     */
    @Column(name = "store_number")
    private String storeNumber;

    /**
     * The machine an approved script will run on.
     *
     * Blank is a hard stop, not a default: {@link com.company.warden.service.IncidentTarget}
     * will try to read a host out of the ticket text, and if there is none the operator is
     * asked. Nothing guesses which box to restart.
     */
    @Column(name = "target_host")
    private String targetHost;

    /** SSH | WINRM | AGENT. Blank means "executor, use your own default path to the host". */
    @Column(name = "connection_method")
    private String connectionMethod;

    /**
     * The operating system on that machine, as declared by a person: windows | linux | darwin.
     *
     * Blank is the normal case and means "work it out" — the probe reply, then the WinRM
     * connection method, then the procedure's own guess. Set, it outranks all of them,
     * because it is a person's answer to this exact question rather than an inference about
     * it. A value that does not normalise is ignored rather than honoured, so a typo cannot
     * hand a Windows till a bash script.
     */
    @Column(name = "target_platform")
    private String targetPlatform;

    @Column(name = "external_service_name")
    private String externalServiceName = "ServiceNow";

    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachments;

    public Incident() {}

    public Incident(UUID id, String subject, String description, String assignee, String assignedGteam, 
                    String priority, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt, 
                    OffsetDateTime dueDate, String externalSource, String externalId, String category) {
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

    public String getReporterEmail() { return reporterEmail; }
    public void setReporterEmail(String reporterEmail) { this.reporterEmail = reporterEmail; }

    public String getStoreNumber() { return storeNumber; }
    public void setStoreNumber(String storeNumber) { this.storeNumber = storeNumber; }

    public String getTargetHost() { return targetHost; }
    public void setTargetHost(String targetHost) { this.targetHost = targetHost; }

    public String getConnectionMethod() { return connectionMethod; }
    public void setConnectionMethod(String connectionMethod) { this.connectionMethod = connectionMethod; }

    public String getTargetPlatform() { return targetPlatform; }
    public void setTargetPlatform(String targetPlatform) { this.targetPlatform = targetPlatform; }

    public String getExternalServiceName() { return externalServiceName; }
    public void setExternalServiceName(String externalServiceName) { this.externalServiceName = externalServiceName; }

    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }

    /**
     * The host and store this ticket's own words point at — read from subject and
     * description, blank when they point at nothing.
     *
     * Not columns and not answers: they exist so the UI can offer what the ticket already
     * says as a prefill, rather than making a person retype a hostname that is sitting in
     * the description two inches above the field. The person still has to save it, at which
     * point it becomes the typed {@code targetHost} and outranks this.
     *
     * READ_ONLY because this class is also the PUT request body — a client echoing the
     * object it was given back must not be rejected for sending a field it did not invent,
     * and must not be able to set one either.
     *
     * ponytail: computed per serialisation, two regex passes over a few hundred characters.
     * Cache it on the entity if a list endpoint ever shows up in a profile.
     */
    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getDetectedTargetHost() {
        return com.company.warden.service.IncidentTarget.hostInText(subject, description);
    }

    @Transient
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getDetectedStoreNumber() {
        return com.company.warden.service.IncidentTarget.storeInText(subject, description);
    }

    // Builder Pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String subject;
        private String description;
        private String assignee;
        private String assignedGteam;
        private String priority;
        private String status = "New";
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private OffsetDateTime dueDate;
        private String externalSource = "None";
        private String externalId;
        private String category = "General";
        private String reporterEmail;
        private String storeNumber;
        private String targetHost;

        public Builder id(UUID id) { this.id = id; return this; }
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
        public Builder reporterEmail(String reporterEmail) { this.reporterEmail = reporterEmail; return this; }
        public Builder storeNumber(String storeNumber) { this.storeNumber = storeNumber; return this; }
        public Builder targetHost(String targetHost) { this.targetHost = targetHost; return this; }

        public Incident build() {
            Incident incident = new Incident(id, subject, description, assignee, assignedGteam, priority, status, createdAt, updatedAt, dueDate, externalSource, externalId, category);
            incident.setReporterEmail(reporterEmail);
            incident.setStoreNumber(storeNumber);
            incident.setTargetHost(targetHost);
            return incident;
        }
    }
}
