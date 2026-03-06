package com.company.mcp.controller;

import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
import com.company.mcp.service.SopDocumentParser;
import com.company.mcp.service.SopLinkedAssetService;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final SopDocumentParser documentParser;
    private final SopLinkedAssetService sopLinkedAssetService;

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
            return ApiErrorResponses.badRequest();
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
            return ApiErrorResponses.badRequest();
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
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Get SOPs for a tenant.
     */
    @GetMapping
    public ResponseEntity<?> getSopsByTenant(@RequestParam String tenantId) {
        try {
            UUID tenantUuid = UUID.fromString(tenantId);
            List<SopProcedure> sops = sopRepository.findByTenantIdOrderByUpdatedAtDesc(tenantUuid);

            Map<String, Object> response = new HashMap<>();
            response.put("count", sops.size());
            response.put("sops", sops);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get SOPs", e);
            return ApiErrorResponses.badRequest();
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
            return ApiErrorResponses.badRequest();
        }
    }

    @DeleteMapping("/{sopId}")
    public ResponseEntity<?> deleteSop(@PathVariable UUID sopId) {
        try {
            boolean deleted = sopLinkedAssetService.deleteSopAndLinkedAssets(sopId);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                    "id", sopId,
                    "message", "SOP deleted successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to delete SOP {}", sopId, e);
            return ApiErrorResponses.badRequest();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Document Upload — parse SOP from PDF/DOCX/XLSX/TXT for user validation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/sops/parse-text
     * Accepts raw text/markdown content as JSON — NO file upload needed.
     * Parses the content and returns extracted SOP fields.
     * The user validates the fields in the UI then calls POST /api/v1/sops to save.
     *
     * Request body:
     * <pre>
     * {
     *   "content": "# SOP: Tomcat API URL Not Accessible\n...",
     *   "fileName": "TOMCAT_URL_FIX.md"   // optional, used for heuristics
     * }
     * </pre>
     */
    @PostMapping("/parse-text")
    public ResponseEntity<?> parseTextContent(@RequestBody Map<String, Object> body) {
        String content  = body.get("content")  != null ? body.get("content").toString()  : "";
        String fileName = body.get("fileName") != null ? body.get("fileName").toString() : "pasted-content.md";

        if (content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Content is required"));
        }

        try {
            SopDocumentParser.ParsedSop parsed = documentParser.parseRawText(content, fileName);
            Map<String, Object> resp = new HashMap<>();
            resp.put("title",           parsed.title());
            resp.put("description",     parsed.description());
            resp.put("resolutionSteps", parsed.resolutionSteps());
            resp.put("mcpToolScript",   parsed.mcpToolScript());
            resp.put("sourceFileName",  parsed.sourceFileName());
            resp.put("warnings",        parsed.warnings());
            log.info("SopController: parsed text content — title='{}'", parsed.title());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to parse SOP text content", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * POST /api/v1/sops/parse-and-save
     * Accepts raw text/markdown content, parses it, extracts SOP fields,
     * and saves directly to the database in one call — no file storage.
     *
     * Request body:
     * <pre>
     * {
     *   "content": "# SOP: Tomcat API URL Not Accessible\n...",
     *   "fileName": "TOMCAT_URL_FIX.md",
     *   "tenantId": "00000000-0000-0000-0000-000000000001",
     *   "ownerTeam": "Platform SRE"
     * }
     * </pre>
     */
    @PostMapping("/parse-and-save")
    public ResponseEntity<?> parseAndSave(@RequestBody Map<String, Object> body,
                                          @RequestParam(defaultValue = "system") String createdBy) {
        String content   = body.get("content")  != null ? body.get("content").toString()  : "";
        String fileName  = body.get("fileName") != null ? body.get("fileName").toString() : "pasted-content.md";
        String ownerTeam = body.get("ownerTeam") != null ? body.get("ownerTeam").toString() : "";

        if (content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Content is required"));
        }

        try {
            SopDocumentParser.ParsedSop parsed = documentParser.parseRawText(content, fileName);

            SopProcedure sop = SopProcedure.builder()
                    .id(UUID.randomUUID())
                    .title(parsed.title() != null ? parsed.title() : "Untitled SOP")
                    .category("GENERAL")
                    .description(parsed.description())
                    .actionPlanJson(parsed.resolutionSteps())
                    .ownerTeam(ownerTeam)
                    .status("DRAFT")
                    .approvedBy(null)
                    .scope("PRIVATE")
                    .version("v1.0")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            if (body.get("tenantId") != null) {
                sop.setTenantId(UUID.fromString(body.get("tenantId").toString()));
            }

            SopProcedure saved = sopLinkedAssetService.saveWithLinkedAssets(
                    sop,
                    parsed.mcpToolScript(),
                    createdBy
            );
            log.info("SopController: parse-and-save '{}' by '{}'", saved.getTitle(), createdBy);

            return ResponseEntity.ok(buildSaveResponse(
                    saved,
                    "SOP parsed, linked to an MCP tool, and saved as DRAFT — approve to activate",
                    parsed.warnings()
            ));
        } catch (Exception e) {
            log.error("Failed to parse-and-save SOP", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * POST /api/v1/sops/parse
     * Accepts a multipart file upload and returns extracted SOP fields.
     * The user validates the fields in the UI then calls POST /api/v1/sops to save.
     */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!name.endsWith(".pdf") && !name.endsWith(".docx") && !name.endsWith(".txt")
                && !name.endsWith(".md") && !name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported file type. Supported: PDF, DOCX, XLSX, TXT, MD"));
        }
        try {
            SopDocumentParser.ParsedSop parsed = documentParser.parse(file);
            Map<String, Object> resp = new HashMap<>();
            resp.put("title",            parsed.title());
            resp.put("description",      parsed.description());
            resp.put("resolutionSteps",  parsed.resolutionSteps());
            resp.put("mcpToolScript",    parsed.mcpToolScript());
            resp.put("sourceFileName",   parsed.sourceFileName());
            resp.put("warnings",         parsed.warnings());
            log.info("SopController: parsed document '{}' — title='{}'",
                    file.getOriginalFilename(), parsed.title());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Failed to parse SOP document", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * POST /api/v1/sops/upload-and-save
     * Accepts a multipart file upload, extracts text, parses via LLM,
     * and saves directly to DB in one call. No file is stored on the server.
     */
    @PostMapping(value = "/upload-and-save", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndSave(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) String tenantId,
                                           @RequestParam(defaultValue = "system") String createdBy) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }
        try {
            SopDocumentParser.ParsedSop parsed = documentParser.parse(file);

            SopProcedure sop = SopProcedure.builder()
                    .id(UUID.randomUUID())
                    .title(parsed.title() != null ? parsed.title() : "Untitled SOP")
                    .category("GENERAL")
                    .description(parsed.description())
                    .actionPlanJson(parsed.resolutionSteps())
                    .status("DRAFT")
                    .approvedBy(null)
                    .scope("PRIVATE")
                    .version("v1.0")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            if (tenantId != null && !tenantId.isBlank()) {
                sop.setTenantId(UUID.fromString(tenantId));
            }

            SopProcedure saved = sopLinkedAssetService.saveWithLinkedAssets(
                    sop,
                    parsed.mcpToolScript(),
                    createdBy
            );
            log.info("SopController: upload-and-save '{}' from file '{}' by '{}'",
                    saved.getTitle(), file.getOriginalFilename(), createdBy);

            return ResponseEntity.ok(buildSaveResponse(
                    saved,
                    "SOP parsed from file, linked to an MCP tool, and saved as DRAFT",
                    parsed.warnings()
            ));
        } catch (Exception e) {
            log.error("Failed to upload-and-save SOP from file", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * POST /api/v1/sops/manual
     * Save a manually-entered SOP (no file upload required).
     * Accepts flat fields and converts to SopProcedure with DRAFT status.
     */
    @PostMapping("/manual")
    public ResponseEntity<?> createManual(@RequestBody Map<String, Object> body,
                                          @RequestParam(defaultValue = "system") String createdBy) {
        try {
            SopProcedure sop = SopProcedure.builder()
                    .id(UUID.randomUUID())
                    .title((String) body.getOrDefault("title", "Untitled SOP"))
                    .category("GENERAL")
                    .description((String) body.get("description"))
                    .actionPlanJson((String) body.get("resolutionSteps"))
                    .ownerTeam((String) body.get("ownerTeam"))
                    .status("DRAFT")
                    .approvedBy(null)
                    .scope("PRIVATE")
                    .version("v1.0")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // Optional tenant from body
            if (body.get("tenantId") != null) {
                sop.setTenantId(UUID.fromString((String) body.get("tenantId")));
            }

            String mcpToolScript = body.get("mcpToolScript") != null
                    ? body.get("mcpToolScript").toString()
                    : null;
            SopProcedure saved = sopLinkedAssetService.saveWithLinkedAssets(
                    sop,
                    mcpToolScript,
                    createdBy
            );
            log.info("SopController: manual SOP created '{}' by '{}'", saved.getTitle(), createdBy);
            return ResponseEntity.ok(buildSaveResponse(
                    saved,
                    saved.getLinkedToolId() != null
                            ? "SOP saved as DRAFT and linked to its MCP tool — approve to activate"
                            : "SOP saved as DRAFT — approve to activate",
                    null
            ));
        } catch (Exception e) {
            log.error("Failed to create manual SOP", e);
            return ApiErrorResponses.badRequest();
        }
    }

    private Map<String, Object> buildSaveResponse(
            SopProcedure saved,
            String message,
            List<String> warnings) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", saved.getId());
        resp.put("title", saved.getTitle());
        resp.put("status", saved.getStatus());
        resp.put("message", message);
        resp.put("linkedToolId", saved.getLinkedToolId());
        resp.put("linkedToolName", saved.getLinkedToolName());
        resp.put("linkedScriptId", saved.getLinkedScriptId());
        resp.put("linkedScriptName", saved.getLinkedScriptName());
        if (warnings != null && !warnings.isEmpty()) {
            resp.put("warnings", warnings);
        }
        return resp;
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
