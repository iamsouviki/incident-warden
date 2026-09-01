package com.company.mcp.controller;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.service.integration.IntegrationManagerService;
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
    private final CurrentUser currentUser;

    public IntegrationController(IntegrationManagerService integrationManager,
                                 IncidentRepository incidentRepository,
                                 CurrentUser currentUser) {
        this.integrationManager = integrationManager;
        this.incidentRepository = incidentRepository;
        this.currentUser = currentUser;
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
        Map<String, Object> result = integrationManager.syncAllEnabled(currentUser.tenantId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/incidents/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        Incident inc = incidentRepository.findById(id).orElse(null);
        if (inc == null) return ResponseEntity.notFound().build();

        String status = body.getOrDefault("status", "In Progress");
        boolean ok = integrationManager.updateExternalStatus(inc, status);
        inc.setStatus(status);
        incidentRepository.save(inc);

        return ResponseEntity.ok(Map.of("updated", ok, "status", status));
    }

    @PostMapping("/incidents/{id}/notes")
    public ResponseEntity<?> addNote(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        Incident inc = incidentRepository.findById(id).orElse(null);
        if (inc == null) return ResponseEntity.notFound().build();

        String note = body.getOrDefault("note", "");
        if (note.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Note content is required"));

        boolean ok = integrationManager.addExternalWorkNote(inc, note);
        return ResponseEntity.ok(Map.of("success", ok, "message", "Note pushed to external service."));
    }

    @GetMapping("/incidents/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id, @PathVariable String attachmentId) {
        Incident inc = incidentRepository.findById(id).orElse(null);
        if (inc == null) return ResponseEntity.notFound().build();

        byte[] data = integrationManager.downloadExternalAttachment(inc, attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"incident-" + inc.getExternalId() + "-attachment.log\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
