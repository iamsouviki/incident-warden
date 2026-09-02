package com.company.warden.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "execution_logs", schema = "tools")
public class ExecutionLog {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "script_id")
    private UUID scriptId;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "agent")
    private String agent;

    @Column(name = "phase")
    private String phase;

    @Column(name = "validation_status")
    private String validationStatus;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "timestamp")
    private OffsetDateTime timestamp;

    @Column(name = "script_content", nullable = false, columnDefinition = "TEXT")
    private String scriptContent;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "exit_code", nullable = false)
    private Integer exitCode;

    @Column(name = "stdout", columnDefinition = "TEXT")
    private String stdout;

    @Column(name = "stderr", columnDefinition = "TEXT")
    private String stderr;

    public ExecutionLog() {}

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (timestamp == null) {
            timestamp = OffsetDateTime.now();
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getScriptId() { return scriptId; }
    public void setScriptId(UUID scriptId) { this.scriptId = scriptId; }

    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public OffsetDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(OffsetDateTime timestamp) { this.timestamp = timestamp; }

    public String getScriptContent() { return scriptContent; }
    public void setScriptContent(String scriptContent) { this.scriptContent = scriptContent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }

    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }
}
