package com.company.warden.service;

import com.company.warden.model.Incident;
import com.company.warden.model.TelemetryEvent;
import com.company.warden.repository.TelemetryEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TelemetryService {
    private final TelemetryEventRepository events;
    private final IncidentService incidentService;

    @Value("${mcp.telemetry.auto-create-incidents:true}")
    private boolean autoCreateIncidents;

    public TelemetryService(TelemetryEventRepository events, IncidentService incidentService) {
        this.events = events;
        this.incidentService = incidentService;
    }

    public Map<String, Object> ingest(TelemetryEvent event) {
        if (event.getDeviceId() == null || event.getDeviceId().isBlank()
                || event.getStoreId() == null || event.getStoreId().isBlank()
                || event.getEventType() == null || event.getEventType().isBlank()) {
            throw new IllegalArgumentException("deviceId, storeId, and eventType are required");
        }
        event.setReceivedAt(event.getReceivedAt() == null ? OffsetDateTime.now() : event.getReceivedAt());
        event.setStatus("RECEIVED");
        TelemetryEvent saved = events.save(event);
        Incident incident = null;
        if (autoCreateIncidents && isActionable(event)) {
            String correlationKey = "TEL-" + event.getStoreId() + ":" + event.getDeviceId() + ":" + event.getEventType();
            var existing = incidentService.findTelemetryIncident(correlationKey);
            if (existing.isPresent() && !"RESOLVED".equalsIgnoreCase(existing.get().getStatus())) {
                saved.setStatus("INCIDENT_DEDUPLICATED");
                events.save(saved);
                return Map.of("telemetryId", saved.getId(), "incidentId", existing.get().getId(), "status", saved.getStatus());
            }
            incident = incidentService.createIncident(Incident.builder()
                    .subject("[" + event.getStoreId() + "] " + event.getDeviceId() + " - " + event.getEventType())
                    .description(event.getMessage() == null ? "Store device telemetry event" : event.getMessage())
                    .priority(priority(event.getSeverity()))
                    .category("Store Device")
                    .externalSource("Telemetry")
                    .externalId(correlationKey)
                    .assignee("Autonomous Operations")
                    .assignedGteam("Store Device Ops")
                    .build());
            saved.setStatus("INCIDENT_CREATED");
            events.save(saved);
        }
        return Map.of("telemetryId", saved.getId(), "incidentId", incident == null ? "" : incident.getId(),
                "status", saved.getStatus());
    }

    public List<TelemetryEvent> recent() {
        return events.findTop100ByOrderByReceivedAtDesc();
    }

    private boolean isActionable(TelemetryEvent event) {
        String severity = String.valueOf(event.getSeverity()).toUpperCase(Locale.ROOT);
        String type = event.getEventType().toUpperCase(Locale.ROOT);
        return severity.equals("CRITICAL") || severity.equals("HIGH") || type.contains("OFFLINE") || type.contains("ERROR") || type.contains("FAIL");
    }

    private String priority(String severity) {
        return switch (String.valueOf(severity).toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "P1";
            case "HIGH" -> "P2";
            default -> "P3";
        };
    }
}
