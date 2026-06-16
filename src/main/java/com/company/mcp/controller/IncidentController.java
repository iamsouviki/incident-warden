package com.company.mcp.controller;

import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentComment;
import com.company.mcp.model.IncidentHistory;
import com.company.mcp.service.IncidentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    @Autowired
    private IncidentService incidentService;

    @PostMapping
    public ResponseEntity<Incident> createIncident(@RequestBody Incident incident) {
        Incident created = incidentService.createIncident(incident);
        return ResponseEntity.ok(created);
    }

    @GetMapping
    public ResponseEntity<List<Incident>> getIncidents(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String assignedGteam,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String createdDate,
            @RequestParam(required = false) String updatedDate,
            @RequestParam(required = false) String dueDate) {
        
        List<Incident> results = incidentService.searchIncidents(
                subject, description, assignee, assignedGteam,
                priority, createdDate, updatedDate, dueDate
        );
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Incident> getIncident(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentService.getIncidentById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Incident> updateIncident(
            @PathVariable UUID id,
            @RequestBody Incident incident,
            @RequestParam(required = false, defaultValue = "User") String username) {
        Incident updated = incidentService.updateIncident(id, incident, username);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}/comments")
    public ResponseEntity<List<IncidentComment>> getComments(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentService.getComments(id));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<IncidentComment> addComment(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String author = body.getOrDefault("author", "User");
        String text = body.get("commentText");
        IncidentComment comment = incidentService.addComment(id, author, text);
        return ResponseEntity.ok(comment);
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<IncidentHistory>> getHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentService.getHistory(id));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncIncidents() {
        Map<String, Object> result = incidentService.syncExternalIncidents();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, String>> analyzeIncident(@RequestBody Map<String, String> body) {
        String subject = body.getOrDefault("subject", "");
        String description = body.getOrDefault("description", "");
        Map<String, String> result = incidentService.analyzeIncident(subject, description);
        return ResponseEntity.ok(result);
    }
}
