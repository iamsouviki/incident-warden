package com.company.mcp.controller;

import com.company.mcp.model.ConfidenceLog;
import com.company.mcp.model.HitlRequest;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.util.ApiErrorResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HITL (Human-In-The-Loop) Approval API - Phase 8.
 * Handles human approval workflow for high-confidence incidents.
 * 
 * Used for:
 * - Incidents with 80-99% confidence needing human approval
 * - P1 severity incidents requiring additional scrutiny
 * - Model guardrail violations needing human review
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/hitl")
@RequiredArgsConstructor
public class HitlController {
    private final HitlRequestRepository hitlRepository;
    private final IncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;

    /**
     * Get pending HITL requests for a user/team.
     */
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingRequests(
            @RequestParam String tenantId,
            @RequestParam(required = false) String assignedTo) {
        try {
            List<HitlRequest> pending = hitlRepository.findByStatusOrderByCreatedAtDesc("PENDING");

            // Filter by tenant if needed
            pending = pending.stream()
                .filter(r -> r.getTenantId().toString().equals(tenantId))
                .toList();

            Map<String, Object> response = new HashMap<>();
            response.put("count", pending.size());
            response.put("requests", pending);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get HITL requests", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Get a specific HITL request.
     */
    @GetMapping("/{hitlId}")
    public ResponseEntity<?> getHitlRequest(@PathVariable UUID hitlId) {
        try {
            Optional<HitlRequest> hitl = hitlRepository.findById(hitlId);
            return hitl.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to get HITL request", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Approve an incident (human decision).
     */
    @PostMapping("/{hitlId}/approve")
    public ResponseEntity<?> approveIncident(
            @PathVariable UUID hitlId,
            @RequestParam String decidedBy,
            @RequestBody(required = false) Map<String, Object> modifications) {
        try {
            Optional<HitlRequest> hitl = hitlRepository.findById(hitlId);
            
            if (hitl.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            HitlRequest request = hitl.get();
            request.setStatus("APPROVED");
            request.setDecision("APPROVED");
            request.setDecidedBy(decidedBy);
            request.setDecidedAt(LocalDateTime.now());
            if (modifications != null) {
                // Store modifications as JSON-serializable string
                request.setModifications(objectMapper.writeValueAsString(modifications));
                request.setStatus("MODIFIED");
            }

            HitlRequest updated = hitlRepository.save(request);

            Map<String, Object> response = new HashMap<>();
            response.put("hitlId", updated.getId());
            response.put("incidentId", updated.getIncidentId());
            response.put("status", updated.getStatus());
            response.put("message", "Incident approved");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to approve incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Reject an incident (human decision).
     */
    @PostMapping("/{hitlId}/reject")
    public ResponseEntity<?> rejectIncident(
            @PathVariable UUID hitlId,
            @RequestParam String decidedBy,
            @RequestParam String reason) {
        try {
            Optional<HitlRequest> hitl = hitlRepository.findById(hitlId);
            
            if (hitl.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            HitlRequest request = hitl.get();
            request.setStatus("REJECTED");
            request.setDecision("REJECTED");
            request.setDecidedBy(decidedBy);
            request.setDecisionReason(reason);
            request.setDecidedAt(LocalDateTime.now());

            HitlRequest updated = hitlRepository.save(request);

            Map<String, Object> response = new HashMap<>();
            response.put("hitlId", updated.getId());
            response.put("incidentId", updated.getIncidentId());
            response.put("status", updated.getStatus());
            response.put("message", "Incident rejected");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to reject incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Escalate an incident to senior analyst.
     */
    @PostMapping("/{hitlId}/escalate")
    public ResponseEntity<?> escalateIncident(
            @PathVariable UUID hitlId,
            @RequestParam String reason) {
        try {
            Optional<HitlRequest> hitl = hitlRepository.findById(hitlId);
            
            if (hitl.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            HitlRequest request = hitl.get();
            request.setStatus("ESCALATED");
            request.setDecisionReason("Escalated: " + reason);

            HitlRequest updated = hitlRepository.save(request);

            return ResponseEntity.ok(Map.of(
                "hitlId", updated.getId(),
                "message", "Incident escalated for senior review"
            ));
        } catch (Exception e) {
            log.error("Failed to escalate incident", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Get HITL statistics.
     */
    @GetMapping("/stats/{tenantId}")
    public ResponseEntity<?> getStats(@PathVariable String tenantId) {
        try {
            UUID tenantUuid = UUID.fromString(tenantId);
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("pending", hitlRepository.countByTenantIdAndStatus(tenantUuid, "PENDING"));
            stats.put("approved", hitlRepository.countByTenantIdAndStatus(tenantUuid, "APPROVED"));
            stats.put("rejected", hitlRepository.countByTenantIdAndStatus(tenantUuid, "REJECTED"));
            stats.put("escalated", hitlRepository.countByTenantIdAndStatus(tenantUuid, "ESCALATED"));

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Failed to get HITL stats", e);
            return ApiErrorResponses.badRequest();
        }
    }
}
