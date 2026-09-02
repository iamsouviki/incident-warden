package com.company.warden.controller;

import com.company.warden.model.Incident;
import com.company.warden.repository.IncidentRepository;
import com.company.warden.service.integration.IntegrationManagerService;
import com.company.warden.service.integration.SourceUpdate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {

    private final IntegrationManagerService integrationManager;
    private final IncidentRepository incidentRepository;

    public IntegrationController(IntegrationManagerService integrationManager,
                                 IncidentRepository incidentRepository) {
        this.integrationManager = integrationManager;
        this.incidentRepository = incidentRepository;
    }

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(integrationManager.getAllIntegrationSettings());
    }

    @PostMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> body) {
        integrationManager.updateIntegrationSettings(body);
        return ResponseEntity.ok(Map.of("message", "Integration settings updated successfully."));
    }

    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@RequestBody Map<String, String> body) {
        String service = body.getOrDefault("service", "ServiceNow");
        boolean ok = integrationManager.testConnection(service);
        return ResponseEntity.ok(Map.of(
                "service", service,
                "connected", ok,
                "status", ok ? "Connection verified successfully." : "Connection failed. Please check credentials or host reachability."
        ));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> triggerSync() {
        return ResponseEntity.ok(integrationManager.syncAllEnabled());
    }

    @PostMapping("/incidents/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        Incident inc = incidentRepository.findById(id).orElse(null);
        if (inc == null) return ResponseEntity.notFound().build();

        String status = body.getOrDefault("status", "In Progress");
        SourceUpdate result = integrationManager.updateExternalStatus(inc, status);
        inc.setStatus(status);
        incidentRepository.save(inc);

        // The local status always changes; whether the source ticket followed is reported separately
        // so the UI cannot present "not configured" or "rejected" as a completed round trip.
        return ResponseEntity.ok(Map.of("status", status, "sourceUpdate", result.name()));
    }

    @PostMapping("/incidents/{id}/notes")
    public ResponseEntity<?> addNote(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        Incident inc = incidentRepository.findById(id).orElse(null);
        if (inc == null) return ResponseEntity.notFound().build();

        String note = body.getOrDefault("note", "");
        if (note.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Note content is required"));

        SourceUpdate result = integrationManager.addExternalWorkNote(inc, note);
        return ResponseEntity.ok(Map.of("sourceUpdate", result.name()));
    }

    @GetMapping("/incidents/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id, @PathVariable String attachmentId) {
        Incident inc = incidentRepository.findById(id).orElse(null);
        if (inc == null) return ResponseEntity.notFound().build();

        byte[] data = integrationManager.downloadExternalAttachment(inc, attachmentId);
        if (data == null) return ResponseEntity.status(502).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"incident-" + inc.getExternalId() + "-attachment.log\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
