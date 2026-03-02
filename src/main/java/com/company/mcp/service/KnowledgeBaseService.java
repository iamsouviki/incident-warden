package com.company.mcp.service;

import com.company.mcp.model.Incident;
import com.company.mcp.model.ResolvedIncidentKb;
import com.company.mcp.repository.ResolvedIncidentKbRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * KnowledgeBaseService — manages the Resolved Incident Knowledge Base.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li><b>Archive</b>: when an incident reaches a terminal state, call
 *       {@link #archiveResolved} to store it in the {@code resolved_incident_kb}
 *       table, capturing resolution details and operator comments.</li>
 *   <li><b>Embed</b>: a {@link Scheduled} job picks up rows whose
 *       {@code embedding_ingested} flag is {@code false} and pushes them to the
 *       pgvector VectorStore via {@link RagService} so they are searchable.</li>
 *   <li><b>Search</b>: {@link #search} combines ILIKE text search with optional
 *       semantic similarity (RAG) to surface past resolutions similar to any
 *       given incident description.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final ResolvedIncidentKbRepository kbRepository;
    private final RagService ragService;

    // ── Archive ───────────────────────────────────────────────────────────────

    /**
     * Archive a resolved incident into the knowledge base.
     *
     * <p>If an entry for the same incident already exists (by {@code incidentId}
     * or {@code sourceTicketId}) it is updated in-place rather than duplicated.
     *
     * @param incident         The resolved {@link Incident} entity
     * @param resolutionSummary Brief description of how it was fixed (may be null)
     * @param rootCause        Identified root cause (may be null)
     * @param resolutionSteps  Ordered list of actions taken
     * @param comments         Operator / HITL comments collected during handling
     * @param resolvedBy       "AUTO" or the username of the operator who resolved it
     * @return The persisted {@link ResolvedIncidentKb} entry
     */
    @Transactional
    public ResolvedIncidentKb archiveResolved(
            Incident incident,
            String resolutionSummary,
            String rootCause,
            List<Map<String, Object>> resolutionSteps,
            List<Map<String, Object>> comments,
            String resolvedBy
    ) {
        if (incident == null) throw new IllegalArgumentException("incident must not be null");

        // Upsert: find existing entry by incident ID or ticket ID
        Optional<ResolvedIncidentKb> existing = incident.getId() != null
                ? kbRepository.findByIncidentId(incident.getId())
                : Optional.empty();

        if (existing.isEmpty() && incident.getSourceTicketId() != null) {
            existing = kbRepository.findBySourceTicketId(incident.getSourceTicketId());
        }

        ResolvedIncidentKb entry = existing.orElseGet(() -> ResolvedIncidentKb.builder()
                .incidentId(incident.getId())
                .tenantId(incident.getTenantId())
                .sourceSystem(incident.getSourceSystem() != null ? incident.getSourceSystem() : "unknown")
                .sourceTicketId(incident.getSourceTicketId())
                .build());

        // Always update with latest resolution data
        entry.setTitle(incident.getTitle());
        entry.setDescription(incident.getDescription());
        entry.setCategory(incident.getCategory());
        entry.setSubCategory(incident.getSubCategory());
        entry.setSeverity(incident.getSeverity() != null ? incident.getSeverity() : "P3");
        entry.setAffectedSystems(incident.getAffectedSystems());
        entry.setResolutionSummary(resolutionSummary);
        entry.setRootCause(rootCause);
        entry.setResolutionSteps(resolutionSteps != null ? resolutionSteps : List.of());
        entry.setComments(comments != null ? comments : List.of());
        entry.setResolvedBy(resolvedBy != null ? resolvedBy : "SYSTEM");
        entry.setOriginalStatus(incident.getStatus());
        entry.setConfidenceScore(incident.getConfidenceScore());
        entry.setMatchedSopId(incident.getMatchedSopId());
        entry.setResolvedAt(incident.getResolvedAt() != null ? incident.getResolvedAt() : LocalDateTime.now());
        entry.setEmbeddingIngested(false); // mark for re-embedding on update

        ResolvedIncidentKb saved = kbRepository.save(entry);
        log.info("[KB] Archived resolved incident {} → KB entry {}", incident.getId(), saved.getId());

        // Try immediate embedding (best-effort)
        ingestSingleEntry(saved);

        return saved;
    }

    /**
     * Manually add a KB entry (e.g. imported from an external ticket system).
     */
    @Transactional
    public ResolvedIncidentKb addManualEntry(ResolvedIncidentKb entry) {
        entry.setEmbeddingIngested(false);
        ResolvedIncidentKb saved = kbRepository.save(entry);
        ingestSingleEntry(saved);
        return saved;
    }

    /**
     * Append a comment to an existing KB entry.
     *
     * @param kbId    Knowledge-base entry ID
     * @param author  Comment author (username or "AUTO")
     * @param role    Role (e.g. "OPERATOR", "AUTO", "SYSTEM")
     * @param text    Comment body
     * @return Updated KB entry
     */
    @Transactional
    public ResolvedIncidentKb addComment(UUID kbId, String author, String role, String text) {
        ResolvedIncidentKb entry = kbRepository.findById(kbId)
                .orElseThrow(() -> new NoSuchElementException("KB entry not found: " + kbId));

        List<Map<String, Object>> comments = new ArrayList<>(
                entry.getComments() != null ? entry.getComments() : List.of());

        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("author", author);
        comment.put("role",   role);
        comment.put("text",   text);
        comment.put("ts",     LocalDateTime.now().toString());
        comments.add(comment);

        entry.setComments(comments);
        entry.setEmbeddingIngested(false); // re-embed so comments are searchable

        return kbRepository.save(entry);
    }

    // ── Search & Query ────────────────────────────────────────────────────────

    /**
     * Search the knowledge base using both keyword ILIKE and optional semantic
     * similarity (if the pgvector VectorStore is available).
     *
     * @param tenantId  Tenant scoping
     * @param query     Free-text search string
     * @param topK      Maximum results to return
     * @return Deduplicated list of KB entries, most relevant first
     */
    public List<ResolvedIncidentKb> search(UUID tenantId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return kbRepository.findByTenantIdOrderByResolvedAtDesc(tenantId)
                    .stream().limit(topK).collect(Collectors.toList());
        }

        // 1. ILIKE text search (always available)
        List<ResolvedIncidentKb> textResults = kbRepository.fullTextSearch(tenantId, query);

        // 2. Vector similarity search (available only when VectorStore is active)
        List<ResolvedIncidentKb> vectorResults = List.of();
        if (ragService.isVectorStoreAvailable()) {
            List<Document> similar = ragService.findSimilarResolved(query, topK);
            List<UUID> vectorIds = similar.stream()
                    .map(d -> d.getMetadata().get("kb_id"))
                    .filter(Objects::nonNull)
                    .map(id -> UUID.fromString(id.toString()))
                    .collect(Collectors.toList());
            if (!vectorIds.isEmpty()) {
                vectorResults = kbRepository.findAllById(vectorIds);
            }
        }

        // Merge — vector results first (higher semantic relevance), then text results
        LinkedHashMap<UUID, ResolvedIncidentKb> merged = new LinkedHashMap<>();
        vectorResults.forEach(e -> merged.put(e.getId(), e));
        textResults.forEach(e -> merged.putIfAbsent(e.getId(), e));

        return merged.values().stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * Paginated listing of all KB entries for a tenant (no search query).
     */
    public Page<ResolvedIncidentKb> list(UUID tenantId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return kbRepository.findByTenantIdOrderByResolvedAtDesc(tenantId, pageable);
    }

    /**
     * Paginated filtered listing by category and/or severity.
     */
    public Page<ResolvedIncidentKb> listFiltered(
            UUID tenantId, String category, String severity, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return kbRepository.search(tenantId, category, severity, pageable);
    }

    /**
     * Return a single KB entry by its ID.
     */
    public Optional<ResolvedIncidentKb> getById(UUID id) {
        return kbRepository.findById(id);
    }

    /**
     * Return KB entries for a given original incident ID.
     */
    public Optional<ResolvedIncidentKb> getByIncidentId(UUID incidentId) {
        return kbRepository.findByIncidentId(incidentId);
    }

    // ── Embedding ingestion ───────────────────────────────────────────────────

    /**
     * Scheduled job: pick up KB entries that have not been embedded yet
     * and ingest them into the pgvector VectorStore.
     * Runs every 2 minutes.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 30_000)
    @Transactional
    public void embedPendingEntries() {
        if (!ragService.isVectorStoreAvailable()) return;

        List<ResolvedIncidentKb> pending = kbRepository.findPendingEmbedding();
        if (pending.isEmpty()) return;

        log.info("[KB] Embedding {} pending KB entries into VectorStore", pending.size());

        List<UUID> successIds = new ArrayList<>();
        for (ResolvedIncidentKb entry : pending) {
            if (ingestSingleEntry(entry)) {
                successIds.add(entry.getId());
            }
        }

        if (!successIds.isEmpty()) {
            kbRepository.markEmbeddingIngested(successIds);
            log.info("[KB] Marked {} KB entries as embedded", successIds.size());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Attempt to ingest one KB entry into the VectorStore.
     * Returns {@code true} on success and marks the entry as ingested.
     */
    private boolean ingestSingleEntry(ResolvedIncidentKb entry) {
        if (!ragService.isVectorStoreAvailable()) return false;
        try {
            boolean ok = ragService.ingestResolvedIncident(entry);
            if (ok) {
                entry.setEmbeddingIngested(true);
                kbRepository.save(entry);
            }
            return ok;
        } catch (Exception e) {
            log.warn("[KB] Embedding failed for KB entry {}: {}", entry.getId(), e.getMessage());
            return false;
        }
    }
}
