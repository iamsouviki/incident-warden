package com.company.warden.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "telemetry_events", schema = "incident")
public class TelemetryEvent {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String deviceId;
    @Column(nullable = false)
    private String storeId;
    private String deviceType;
    @Column(nullable = false)
    private String eventType;
    private String severity;
    @Column(columnDefinition = "TEXT")
    private String message;
    private String status;
    private OffsetDateTime receivedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (receivedAt == null) receivedAt = OffsetDateTime.now();
        if (status == null) status = "RECEIVED";
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
}
