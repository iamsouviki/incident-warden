package com.company.warden.controller;

import com.company.warden.config.CurrentUser;
import com.company.warden.model.SopProcedure;
import com.company.warden.repository.SopProcedureRepository;
import com.company.warden.service.RagService;
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
    private final com.company.warden.service.RateLimiterService rateLimiter;
    private final com.company.warden.service.ChatSessionService chatSessionService;

    public RagController(RagService ragService, CurrentUser currentUser, SopProcedureRepository procedures,
            com.company.warden.service.RateLimiterService rateLimiter,
            com.company.warden.service.ChatSessionService chatSessionService) {
        this.ragService = ragService;
        this.currentUser = currentUser;
        this.procedures = procedures;
        this.rateLimiter = rateLimiter;
        this.chatSessionService = chatSessionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestSop(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String description = body.get("description");
        if (title == null || description == null || title.isBlank() || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title and description are required"));
        }
        boolean success = ragService.ingestSop(title.trim(), description.trim());
        return success
                ? ResponseEntity.ok(Map.of("message", "SOP successfully ingested and approved for this workspace."))
                : ResponseEntity.status(503)
                        .body(Map.of("error", "SOP service is unavailable; no procedure was stored."));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadSop(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        if (file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "file is empty"));
        boolean success = ragService.ingestFile(file.getResource(), title);
        return success
                ? ResponseEntity.ok(Map.of("message", "File successfully ingested and approved for this workspace."))
                : ResponseEntity.status(503)
                        .body(Map.of("error", "SOP service is unavailable; no procedure was stored."));
    }

    /**
     * Rate limited on the same budget as ticket analysis and script generation.
     * This is the
     * box a user actually types into, and it was the one LLM surface with no
     * ceiling at all:
     * a held-down enter key spent the provider budget one question at a time.
     * Length, blank
     * text and scope are checked inside {@link RagService#refuse}, shared with
     * analysis.
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body, HttpSession session) {
        String question = body.get("question");
        if (question == null || question.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        if (!rateLimiter.allowLlmCall(currentUser.username())) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Too many questions in the last minute. Try again shortly."));
        }
        String sessionIdParam = body.get("sessionId");
        String effSessionId = (sessionIdParam != null && !sessionIdParam.isBlank()) ? sessionIdParam : session.getId();
        String answer = ragService.askStrictSopRag(effSessionId, question);

        // If the client named a stored session, persist the turn into it. A session that is not
        // this user's is skipped rather than refused: the answer above is already correct and
        // paid for, so losing it to a 404 over a history write would be the worse outcome.
        if (sessionIdParam != null && !sessionIdParam.isBlank()) {
            try {
                UUID sessionUuid = UUID.fromString(sessionIdParam);
                chatSessionService.appendMessage(sessionUuid, currentUser.username(), "user", question, null);
                chatSessionService.appendMessage(sessionUuid, currentUser.username(), "bot", answer, null);
            } catch (IllegalArgumentException ignored) {
                // Not a UUID session ID (e.g. servlet session fallback), ignore DB persistence
            }
        }

        return ResponseEntity.ok(Map.of("answer", answer, "sessionId", effSessionId));
    }

    @GetMapping("/sops")
    public ResponseEntity<?> getAllSops() {
        return ResponseEntity.ok(ragService.getAllSops());
    }

    @GetMapping("/procedures")
    public ResponseEntity<?> getApprovedProcedures() {
        return ResponseEntity.ok(procedures.findAllByOrderBySopIdAscStepNumberAsc());
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
        Optional<SopProcedure> opt = procedures.findById(id);
        if (opt.isEmpty())
            return ResponseEntity.notFound().build();
        SopProcedure existing = opt.get();
        if (update.getTitle() != null && !update.getTitle().isBlank())
            existing.setTitle(update.getTitle().trim());
        if (update.getDescription() != null)
            existing.setDescription(update.getDescription().trim());
        if (update.getMatchKeywords() != null)
            existing.setMatchKeywords(update.getMatchKeywords().trim());
        if (update.getActionKey() != null && !update.getActionKey().isBlank())
            existing.setActionKey(update.getActionKey().trim());
        if (update.getApprovalStatus() != null && !update.getApprovalStatus().isBlank())
            existing.setApprovalStatus(update.getApprovalStatus().trim());
        existing.setRequiresApproval(update.isRequiresApproval());
        existing.setExecutionOrder(update.getExecutionOrder());
        existing.setUpdatedAt(OffsetDateTime.now());
        SopProcedure saved = procedures.save(existing);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/procedures/{id}")
    public ResponseEntity<?> deleteProcedure(@PathVariable UUID id) {
        Optional<SopProcedure> opt = procedures.findById(id);
        if (opt.isEmpty())
            return ResponseEntity.notFound().build();
        procedures.delete(opt.get());
        return ResponseEntity.ok(Map.of("message", "Procedure deleted successfully"));
    }

    @PutMapping("/sops/{id}")
    public ResponseEntity<?> updateSop(@PathVariable java.util.UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        String description = body.get("description");
        if (title == null || description == null)
            return ResponseEntity.badRequest().body(Map.of("error", "title and description are required"));
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
