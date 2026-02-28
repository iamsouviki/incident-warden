package com.company.mcp.controller;

import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
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
 * SOP Management API with RAG + Edit UI - Phase 9-10.
 * Allows users to create, edit, and manage Standard Operating Procedures.
 * SOPs are used by the SOP Ranker Agent for incident remediation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sops")
@RequiredArgsConstructor
public class SopController {
    private final SopProcedureRepository sopRepository;

    /**
     * Create a new SOP.
     */
    @PostMapping
    public ResponseEntity<?> createSop(@RequestBody SopProcedure sop) {
        try {
            if (sop.getId() == null) {
                sop.setId(UUID.randomUUID());
            }
            if (sop.getCreatedAt() == null) {
                sop.setCreatedAt(LocalDateTime.now());
            }
            
            SopProcedure created = sopRepository.save(sop);
            
            Map<String, Object> response = new HashMap<>();
            response.put("id", created.getId());
            response.put("title", created.getTitle());
            response.put("status", created.getStatus());
            response.put("message", "SOP created successfully");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to create SOP", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get SOP by ID.
     */
    @GetMapping("/{sopId}")
    public ResponseEntity<?> getSop(@PathVariable UUID sopId) {
        try {
            Optional<SopProcedure> sop = sopRepository.findById(sopId);
            return sop.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to get SOP", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update SOP (with user edits).
     * Used by frontend UI for SOP content editing and versioning.
     */
    @PutMapping("/{sopId}")
    public ResponseEntity<?> updateSop(
            @PathVariable UUID sopId,
            @RequestBody SopProcedure updates) {
        try {
            Optional<SopProcedure> existing = sopRepository.findById(sopId);
            
            if (existing.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            SopProcedure sop = existing.get();
            
            // Update editable fields
            if (updates.getTitle() != null) {
                sop.setTitle(updates.getTitle());
            }
            if (updates.getDescription() != null) {
                sop.setDescription(updates.getDescription());
            }
            if (updates.getActionPlanJson() != null) {
                sop.setActionPlanJson(updates.getActionPlanJson());
            }
            if (updates.getPreconditionsJson() != null) {
                sop.setPreconditionsJson(updates.getPreconditionsJson());
            }
            if (updates.getRollbackStepsJson() != null) {
                sop.setRollbackStepsJson(updates.getRollbackStepsJson());
            }

            // Increment version
            String[] version = sop.getVersion().split("\\.");
            String currentVersion = sop.getVersion();
            sop.setVersion(incrementVersion(currentVersion));
            
            // Update submission
            sop.setStatus("PENDING_APPROVAL");
            sop.setUpdatedAt(LocalDateTime.now());

            SopProcedure updated = sopRepository.save(sop);

            Map<String, Object> response = new HashMap<>();
            response.put("id", updated.getId());
            response.put("title", updated.getTitle());
            response.put("version", updated.getVersion());
            response.put("status", updated.getStatus());
            response.put("message", "SOP updated successfully (pending approval)");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update SOP", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get SOPs for a tenant.
     */
    @GetMapping
    public ResponseEntity<?> getSopsByTenant(@RequestParam String tenantId) {
        try {
            UUID tenantUuid = UUID.fromString(tenantId);
            // Use findByTenantIdAndStatusOrderByVersionDesc for active SOPs
            List<SopProcedure> sops = sopRepository.findByTenantIdAndStatusOrderByVersionDesc(tenantUuid, "ACTIVE");

            Map<String, Object> response = new HashMap<>();
            response.put("count", sops.size());
            response.put("sops", sops);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get SOPs", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Approve SOP for use.
     */
    @PostMapping("/{sopId}/approve")
    public ResponseEntity<?> approveSop(
            @PathVariable UUID sopId,
            @RequestParam String approvedBy) {
        try {
            Optional<SopProcedure> existing = sopRepository.findById(sopId);
            
            if (existing.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            SopProcedure sop = existing.get();
            sop.setStatus("ACTIVE");
            sop.setApprovedBy(approvedBy);

            SopProcedure approved = sopRepository.save(sop);

            return ResponseEntity.ok(Map.of(
                "id", approved.getId(),
                "title", approved.getTitle(),
                "status", approved.getStatus(), 
                "message", "SOP approved and activated"
            ));
        } catch (Exception e) {
            log.error("Failed to approve SOP", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Helper: Increment version number (e.g., "v1.0" -> "v1.1").
     */
    private String incrementVersion(String version) {
        if (!version.startsWith("v")) {
            return "v1.0";
        }

        String numPart = version.substring(1);
        String[] parts = numPart.split("\\.");
        
        try {
            int minor = Integer.parseInt(parts[1]);
            return "v" + parts[0] + "." + (minor + 1);
        } catch (Exception e) {
            return "v1.0";
        }
    }
}
