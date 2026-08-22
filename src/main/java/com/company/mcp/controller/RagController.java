package com.company.mcp.controller;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
import com.company.mcp.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpSession;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagService ragService;
    private final CurrentUser currentUser;
    private final SopProcedureRepository procedures;

    public RagController(RagService ragService, CurrentUser currentUser, SopProcedureRepository procedures) {
        this.ragService = ragService;
        this.currentUser = currentUser;
        this.procedures = procedures;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestSop(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String description = body.get("description");
        if (title == null || description == null || title.isBlank() || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title and description are required"));
        }
        boolean success = ragService.ingestSop(currentUser.tenantId(), title.trim(), description.trim());
        return success ? ResponseEntity.ok(Map.of("message", "SOP successfully ingested and approved for this workspace."))
                : ResponseEntity.status(503).body(Map.of("error", "SOP service is unavailable; no procedure was stored."));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadSop(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "title", required = false) String title) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "file is empty"));
        boolean success = ragService.ingestFile(file.getResource(), title, currentUser.tenantId());
        return success ? ResponseEntity.ok(Map.of("message", "File successfully ingested and approved for this workspace."))
                : ResponseEntity.status(503).body(Map.of("error", "SOP service is unavailable; no procedure was stored."));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body, HttpSession session) {
        String question = body.get("question");
        if (question == null || question.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        return ResponseEntity.ok(Map.of("answer", ragService.askStrictSopRag(session.getId(), question)));
    }

    @GetMapping("/sops")
    public ResponseEntity<?> getAllSops() {
        return ResponseEntity.ok(ragService.getAllSops(currentUser.tenantId()));
    }

    @GetMapping("/procedures")
    public ResponseEntity<?> getApprovedProcedures() {
        return ResponseEntity.ok(procedures.findByTenantIdOrderBySopIdAscStepNumberAsc(currentUser.tenantId()));
    }

    @PostMapping("/procedures")
    public ResponseEntity<?> createProcedure(@RequestBody SopProcedure procedure) {
        if (procedure.getSopId() == null || procedure.getSopId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sopId is required"));
        }
        if (procedure.getTitle() == null || procedure.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        if (procedure.getActionKey() == null || procedure.getActionKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "actionKey is required"));
        }
        if (procedure.getId() == null) {
            procedure.setId(UUID.randomUUID());
        }
        procedure.setTenantId(currentUser.tenantId());
        procedure.setCreatedAt(OffsetDateTime.now());
        procedure.setUpdatedAt(OffsetDateTime.now());
        if (procedure.getApprovalStatus() == null || procedure.getApprovalStatus().isBlank()) {
            procedure.setApprovalStatus("APPROVED");
        }
        if (procedure.getApprovedBy() == null || procedure.getApprovedBy().isBlank()) {
            procedure.setApprovedBy(currentUser.username());
        }
        SopProcedure saved = procedures.save(procedure);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/procedures/{id}")
    public ResponseEntity<?> updateProcedure(@PathVariable UUID id, @RequestBody SopProcedure update) {
        Optional<SopProcedure> opt = procedures.findByIdAndTenantId(id, currentUser.tenantId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        SopProcedure existing = opt.get();
        if (update.getTitle() != null && !update.getTitle().isBlank()) existing.setTitle(update.getTitle().trim());
        if (update.getDescription() != null) existing.setDescription(update.getDescription().trim());
        if (update.getMatchKeywords() != null) existing.setMatchKeywords(update.getMatchKeywords().trim());
        if (update.getActionKey() != null && !update.getActionKey().isBlank()) existing.setActionKey(update.getActionKey().trim());
        if (update.getApprovalStatus() != null && !update.getApprovalStatus().isBlank()) existing.setApprovalStatus(update.getApprovalStatus().trim());
        existing.setRequiresApproval(update.isRequiresApproval());
        existing.setExecutionOrder(update.getExecutionOrder());
        existing.setUpdatedAt(OffsetDateTime.now());
        SopProcedure saved = procedures.save(existing);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/procedures/{id}")
    public ResponseEntity<?> deleteProcedure(@PathVariable UUID id) {
        Optional<SopProcedure> opt = procedures.findByIdAndTenantId(id, currentUser.tenantId());
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        procedures.delete(opt.get());
        return ResponseEntity.ok(Map.of("message", "Procedure deleted successfully"));
    }

    @PutMapping("/sops/{id}")
    public ResponseEntity<?> updateSop(@PathVariable java.util.UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        String description = body.get("description");
        if (title == null || description == null) return ResponseEntity.badRequest().body(Map.of("error", "title and description are required"));
        boolean success = ragService.updateSop(id, title, description);
        return success ? ResponseEntity.ok(Map.of("message", "SOP successfully updated and re-embedded."))
                : ResponseEntity.status(503).body(Map.of("error", "Failed to update SOP."));
    }

    @DeleteMapping("/sops/{id}")
    public ResponseEntity<?> deleteSop(@PathVariable java.util.UUID id) {
        boolean success = ragService.deleteSop(id);
        return success ? ResponseEntity.ok(Map.of("message", "SOP successfully deleted."))
                : ResponseEntity.status(503).body(Map.of("error", "Failed to delete SOP."));
    }
}
