package com.company.mcp.controller;

import com.company.mcp.model.ResolvedIncidentKb;
import com.company.mcp.repository.ResolvedIncidentKbRepository;
import com.company.mcp.service.KnowledgeBaseService;
import com.company.mcp.service.RagService;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Knowledge Base REST API
 *
 * <p>Exposes the Resolved Incident Knowledge Base over HTTP.  Frontend pages
 * and external consumers can:
 * <ul>
 *   <li>Browse / search all resolved incidents ({@code GET /api/v1/kb})</li>
 *   <li>Inspect a single entry with full resolution detail ({@code GET /api/v1/kb/{id}})</li>
 *   <li>Manually archive a resolved incident ({@code POST /api/v1/kb})</li>
 *   <li>Add operator comments to an entry ({@code POST /api/v1/kb/{id}/comments})</li>
 *   <li>Search with semantic similarity using combined SOP + KB RAG
 *       ({@code POST /api/v1/kb/search})</li>
 *   <li>Get an LLM resolution suggestion for a new incident description
 *       ({@code POST /api/v1/kb/suggest})</li>
 *   <li>Return KB statistics for the analytics dashboard ({@code GET /api/v1/kb/stats})</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final ResolvedIncidentKbRepository kbRepository;
    private final RagService ragService;

    // ── Browse ────────────────────────────────────────────────────────────────

    /**
     * List KB entries for a tenant with optional category/severity filter.
     *
     * <pre>
     * GET /api/v1/kb?tenantId=...&page=0&size=20&category=DATABASE&severity=P1
     * </pre>
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam String tenantId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            UUID tid = UUID.fromString(tenantId);
            Page<ResolvedIncidentKb> result = (category != null || severity != null)
                    ? kbService.listFiltered(tid, category, severity, page, size)
                    : kbService.list(tid, page, size);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items",      result.getContent());
            response.put("totalItems", result.getTotalElements());
            response.put("totalPages", result.getTotalPages());
            response.put("page",       page);
            response.put("size",       size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[KB] list failed", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Get a single KB entry by its UUID.
     *
     * <pre>
     * GET /api/v1/kb/{id}
     * </pre>
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        return kbService.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get the KB entry linked to a specific original incident.
     *
     * <pre>
     * GET /api/v1/kb/by-incident/{incidentId}
     * </pre>
     */
    @GetMapping("/by-incident/{incidentId}")
    public ResponseEntity<?> getByIncidentId(@PathVariable UUID incidentId) {
        return kbService.getByIncidentId(incidentId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Text + semantic search across the KB.
     *
     * <p>Combines keyword ILIKE search (always available) with pgvector
     * similarity search (available when a vector store is configured).
     * Returns a ranked, deduplicated list of resolved incidents most similar
     * to the supplied query.
     *
     * <pre>
     * POST /api/v1/kb/search
     * { "tenantId": "...", "query": "redis OOM cache eviction", "topK": 5 }
     * </pre>
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, Object> body) {
        try {
            String tenantId = (String) body.get("tenantId");
            String query    = (String) body.get("query");
            int    topK     = body.containsKey("topK")
                    ? Integer.parseInt(body.get("topK").toString()) : 10;

            if (tenantId == null || query == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "tenantId and query are required"));
            }

            List<ResolvedIncidentKb> results =
                    kbService.search(UUID.fromString(tenantId), query, topK);

            // Augment with semantic similarity docs metadata if available
            List<Map<String, Object>> ragHints = List.of();
            if (ragService.isVectorStoreAvailable()) {
                ragHints = ragService.findSimilarResolved(query, topK).stream()
                        .map(d -> {
                            Map<String, Object> m = new LinkedHashMap<>(d.getMetadata());
                            m.put("snippet", d.getText().substring(0, Math.min(200, d.getText().length())));
                            return m;
                        })
                        .toList();
            }

            return ResponseEntity.ok(Map.of(
                    "results", results,
                    "ragHints", ragHints,
                    "vectorStoreActive", ragService.isVectorStoreAvailable()
            ));
        } catch (Exception e) {
            log.error("[KB] search failed", e);
            return ApiErrorResponses.badRequest();
        }
    }

    // ── Resolution suggestion (combined RAG) ──────────────────────────────────

    /**
     * Ask the combined SOP + KB RAG pipeline for a resolution suggestion.
     *
     * <p>The LLM receives the top-K most relevant passages from <em>both</em>
     * the SOP library and the resolved-incident KB, then synthesises a
     * resolution recommendation citing both sources.
     *
     * <pre>
     * POST /api/v1/kb/suggest
     * { "incidentDescription": "Redis OOM — eviction policy maxmemory-policy ..." }
     * </pre>
     */
    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestBody Map<String, Object> body) {
        try {
            String description = (String) body.get("incidentDescription");
            if (description == null || description.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "incidentDescription is required"));
            }

            // Combined SOP + KB documents for transparency
            List<Document> combinedDocs =
                    ragService.findCombinedContext(description, 6);
            List<Map<String, Object>> sources = combinedDocs.stream()
                    .map(d -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("doc_type", d.getMetadata().getOrDefault("doc_type", ""));
                        m.put("source_id", d.getMetadata().getOrDefault("source_id", ""));
                        m.put("snippet",  d.getText().substring(0, Math.min(250, d.getText().length())));
                        return m;
                    })
                    .toList();

            // LLM suggestion (requires ChatClient — empty string if unavailable)
            String suggestion = ragService.askWithCombinedRag(description);

            return ResponseEntity.ok(Map.of(
                    "suggestion",        suggestion,
                    "sources",           sources,
                    "fullRagAvailable",  ragService.isFullRagAvailable(),
                    "sourcesFound",      combinedDocs.size()
            ));
        } catch (Exception e) {
            log.error("[KB] suggest failed", e);
            return ApiErrorResponses.badRequest();
        }
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Manually create a KB entry (e.g. import from external ticketing system).
     *
     * <pre>
     * POST /api/v1/kb
     * { ResolvedIncidentKb JSON }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody ResolvedIncidentKb entry) {
        try {
            ResolvedIncidentKb saved = kbService.addManualEntry(entry);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("[KB] create failed", e);
            return ApiErrorResponses.badRequest();
        }
    }

    /**
     * Add an operator comment to an existing KB entry.
     *
     * <pre>
     * POST /api/v1/kb/{id}/comments
     * { "author": "john.doe", "role": "OPERATOR", "text": "Root cause confirmed: ..." }
     * </pre>
     */
    @PostMapping("/{id}/comments")
    public ResponseEntity<?> addComment(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        try {
            String author = body.getOrDefault("author", "SYSTEM");
            String role   = body.getOrDefault("role",   "OPERATOR");
            String text   = body.get("text");

            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
            }

            ResolvedIncidentKb updated = kbService.addComment(id, author, role, text);
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("[KB] addComment failed", e);
            return ApiErrorResponses.badRequest();
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /**
     * Return KB statistics for the analytics dashboard.
     *
     * <pre>
     * GET /api/v1/kb/stats?tenantId=...
     * </pre>
     */
    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestParam String tenantId) {
        try {
            UUID tid = UUID.fromString(tenantId);
            long total   = kbRepository.countByTenantId(tid);
            long dbCount = kbRepository.countByTenantIdAndCategory(tid, "DATABASE");
            long appCount = kbRepository.countByTenantIdAndCategory(tid, "APPLICATION");

            // Count entries not yet embedded
            long pendingEmbedding = kbRepository.findPendingEmbedding().stream()
                    .filter(e -> e.getTenantId().equals(tid))
                    .count();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalEntries",       total);
            result.put("pendingEmbedding",   pendingEmbedding);
            result.put("byCategory", Map.of("DATABASE", dbCount, "APPLICATION", appCount));
            result.put("vectorStoreActive",  ragService.isVectorStoreAvailable());
            result.put("fullRagAvailable",   ragService.isFullRagAvailable());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[KB] stats failed", e);
            return ApiErrorResponses.badRequest();
        }
    }
}
