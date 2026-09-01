package com.company.mcp.controller;

import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentComment;
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

    @Autowired
    private com.company.mcp.service.RateLimiterService rateLimiter;

    @Autowired
    private com.company.mcp.config.CurrentUser currentUser;

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

    @PostMapping("/{id}/decision")
    public ResponseEntity<Map<String, Object>> decideIncident(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            Principal principal) {
        String actor = principal != null ? principal.getName() : body.getOrDefault("actor", "User");
        return ResponseEntity.ok(incidentService.decideIncident(
                id,
                body.get("decision"),
                body.get("reason"),
                actor
        ));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentService.getHistory(id));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncIncidents() {
        Map<String, Object> result = incidentService.syncExternalIncidents();
        return ResponseEntity.ok(result);
    }

    /**
     * Rate-limited because it is the priciest endpoint here: two to three model calls plus a
     * public web search per request. Same limiter and same 429 as script generation — an
     * authenticated viewer holding the button down should not be able to spend the whole
     * provider budget.
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, String>> analyzeIncident(@RequestBody Map<String, String> body) {
        if (!rateLimiter.allowLlmCall(currentUser.username())) {
            return ResponseEntity.status(429).body(Map.of("error", "Analysis rate limit reached. Try again in a minute."));
        }
        String subject = body.getOrDefault("subject", "");
        String description = body.getOrDefault("description", "");
        Map<String, String> result = incidentService.analyzeIncident(subject, description);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getAllHistory() {
        return ResponseEntity.ok(List.of());
    }
}
