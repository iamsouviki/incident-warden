package com.company.mcp.controller;

import com.company.mcp.service.IncidentConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final IncidentConversationService incidentConversationService;

    @GetMapping
    public ResponseEntity<?> listThreads(@RequestParam String tenantId) {
        return ResponseEntity.ok(incidentConversationService.listThreads(tenantId));
    }

    @PostMapping
    public ResponseEntity<?> createThread(@RequestBody Map<String, Object> body) {
        UUID incidentId = body.get("incidentId") instanceof String raw && !raw.isBlank()
                ? UUID.fromString(raw) : null;
        String tenantId = String.valueOf(body.get("tenantId"));
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        String createdBy = body.get("createdBy") == null ? null : String.valueOf(body.get("createdBy"));
        return ResponseEntity.ok(incidentConversationService.createThread(tenantId, incidentId, title, createdBy));
    }

    @GetMapping("/{threadId}")
    public ResponseEntity<?> getThread(@PathVariable UUID threadId) {
        return ResponseEntity.ok(incidentConversationService.getThread(threadId));
    }

    @PostMapping("/{threadId}/messages")
    public ResponseEntity<?> addMessage(@PathVariable UUID threadId,
                                        @RequestBody Map<String, Object> body) {
        String role = body.get("role") == null ? "user" : String.valueOf(body.get("role"));
        String messageType = body.get("messageType") == null ? "comment" : String.valueOf(body.get("messageType"));
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredPayload = body.get("structuredPayload") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return ResponseEntity.ok(incidentConversationService.addMessage(threadId, role, messageType, content, structuredPayload));
    }

    @PostMapping("/proposals/{proposalId}/approve")
    public ResponseEntity<?> approveProposal(@PathVariable UUID proposalId,
                                             @RequestParam(defaultValue = "dashboard-user") String approvedBy) {
        return ResponseEntity.ok(incidentConversationService.approveProposal(proposalId, approvedBy));
    }

    @PostMapping("/{threadId}/validation")
    public ResponseEntity<?> validateProposal(@PathVariable UUID threadId,
                                              @RequestBody Map<String, Object> body) {
        boolean resolved = Boolean.parseBoolean(String.valueOf(body.getOrDefault("resolved", false)));
        String confirmedBy = String.valueOf(body.getOrDefault("confirmedBy", "dashboard-user"));
        String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment"));
        return ResponseEntity.ok(incidentConversationService.validateProposal(threadId, resolved, confirmedBy, comment));
    }
}
