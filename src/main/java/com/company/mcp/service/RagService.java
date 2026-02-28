package com.company.mcp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RagService — Retrieval-Augmented Generation using Spring AI 1.0.0 GA.
 *
 * <h3>Core capabilities</h3>
 * <ul>
 *   <li><b>Ingest</b>: Store SOP steps, incident patterns, and runbook content
 *       into the pgvector {@link VectorStore} with metadata for filtering.</li>
 *   <li><b>Retrieve</b>: Similarity search against ingested documents using
 *       the active {@code EmbeddingModel} (OpenAI, Ollama, Anthropic, Gemini).</li>
 *   <li><b>Augment</b>: Wire {@link QuestionAnswerAdvisor} into a
 *       {@link ChatClient} call so the LLM answers using retrieved SOP context.</li>
 * </ul>
 *
 * <h3>Graceful degradation</h3>
 * All methods check whether {@code VectorStore} and {@code ChatClient} are
 * present. When no embedding provider is activated, the service returns empty
 * results without throwing exceptions — the existing pgvector JPA queries in
 * {@code SopRankerAgent} and {@code PatternMatcherAgent} remain the fallback.
 *
 * <h3>Activation (pgvector VectorStore)</h3>
 * <pre>
 *   # 1. Enable a provider with embedding support, e.g. Ollama:
 *   export SPRING_AI_OLLAMA_EMBED_ENABLED=true
 *
 *   # 2. Run PostgreSQL with pgvector extension:
 *   CREATE EXTENSION IF NOT EXISTS vector;
 *
 *   # 3. Start app — Spring AI auto-creates the 'vector_store' table.
 * </pre>
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html">
 *      Spring AI pgvector VectorStore docs</a>
 */
@Slf4j
@Service
public class RagService {

    // ── Spring AI beans (optional — null when no provider enabled) ────────────
    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private ChatClient chatClient;

    // ── Config ────────────────────────────────────────────────────────────────

    @Value("${mcp.rag.top-k:5}")
    private int defaultTopK;

    @Value("${mcp.rag.similarity-threshold:0.6}")
    private double defaultSimilarityThreshold;

    @Value("${mcp.rag.enabled:true}")
    private boolean ragEnabled;

    // ── Document type labels stored as metadata ───────────────────────────────
    public static final String TYPE_SOP     = "SOP";
    public static final String TYPE_PATTERN = "INCIDENT_PATTERN";
    public static final String TYPE_RUNBOOK = "RUNBOOK";

    // ─────────────────────────────────────────────────────────────────────────
    // Ingestion API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ingest a single document into the VectorStore.
     *
     * @param id       Unique identifier (e.g. SOP UUID or incident ID)
     * @param content  Text content to embed
     * @param type     Document type — use {@link #TYPE_SOP}, {@link #TYPE_PATTERN}, etc.
     * @param metadata Additional metadata for filtering (tenantId, category, etc.)
     * @return {@code true} if ingested successfully, {@code false} if VectorStore unavailable
     */
    public boolean ingest(String id, String content, String type, Map<String, Object> metadata) {
        if (!isVectorStoreAvailable()) return false;

        try {
            Map<String, Object> meta = new HashMap<>(metadata != null ? metadata : Map.of());
            meta.put("source_id", id);
            meta.put("doc_type",  type);

            Document doc = Document.builder()
                    .text(content)
                    .metadata(meta)
                    .build();
            vectorStore.add(List.of(doc));
            log.info("[RAG] Ingested document id={} type={}", id, type);
            return true;
        } catch (Exception e) {
            log.error("[RAG] Failed to ingest document id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Batch ingest multiple documents.
     *
     * @param documents List of {@link Document} objects with content and metadata
     * @return number of documents successfully ingested
     */
    public int ingestBatch(List<Document> documents) {
        if (!isVectorStoreAvailable() || documents == null || documents.isEmpty()) return 0;
        try {
            vectorStore.add(documents);
            log.info("[RAG] Batch ingested {} documents", documents.size());
            return documents.size();
        } catch (Exception e) {
            log.error("[RAG] Batch ingest failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Remove a document by its source ID metadata field.
     *
     * @param sourceId the value of the {@code source_id} metadata field
     */
    public void delete(String sourceId) {
        if (!isVectorStoreAvailable()) return;
        try {
            // Spring AI VectorStore filter expression: source_id == '<id>'
            vectorStore.delete(List.of(sourceId));
            log.info("[RAG] Deleted document source_id={}", sourceId);
        } catch (Exception e) {
            log.warn("[RAG] Delete failed for source_id={}: {}", sourceId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retrieval API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Similarity search — returns top-K documents most similar to the query.
     *
     * @param query     Free-text query (will be embedded using the active EmbeddingModel)
     * @param topK      Maximum number of results
     * @param threshold Minimum cosine similarity score (0.0–1.0)
     * @return Ordered list of matching {@link Document} objects, most similar first
     */
    public List<Document> findSimilar(String query, int topK, double threshold) {
        if (!isVectorStoreAvailable() || query == null || query.isBlank()) return List.of();

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            log.debug("[RAG] Similarity search '{}' → {} results (topK={}, threshold={})",
                    query.substring(0, Math.min(60, query.length())), results.size(), topK, threshold);
            return results;
        } catch (Exception e) {
            log.error("[RAG] Similarity search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Convenience overload using configured defaults.
     */
    public List<Document> findSimilar(String query) {
        return findSimilar(query, defaultTopK, defaultSimilarityThreshold);
    }

    /**
     * Find similar SOPs only (filtered by doc_type metadata).
     */
    public List<Document> findSimilarSops(String query, int topK) {
        if (!isVectorStoreAvailable()) return List.of();
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(defaultSimilarityThreshold)
                    .filterExpression("doc_type == '" + TYPE_SOP + "'")
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("[RAG] SOP similarity search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Find similar incident patterns only.
     */
    public List<Document> findSimilarPatterns(String query, int topK) {
        if (!isVectorStoreAvailable()) return List.of();
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(defaultSimilarityThreshold)
                    .filterExpression("doc_type == '" + TYPE_PATTERN + "'")
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("[RAG] Pattern similarity search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Augmented Generation API (RAG pipeline)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ask a question using RAG — retrieves relevant SOP context first,
     * then sends it along with the question to the LLM via {@link QuestionAnswerAdvisor}.
     *
     * <p>Requires both {@code VectorStore} and {@code ChatClient} to be active.
     *
     * @param question The question to answer (e.g. "How do I restart Tomcat safely?")
     * @return LLM answer grounded in retrieved SOP context, or empty string if unavailable
     */
    public String askWithRag(String question) {
        if (!isVectorStoreAvailable() || chatClient == null) {
            log.warn("[RAG] askWithRag unavailable — VectorStore={} ChatClient={}",
                    isVectorStoreAvailable(), chatClient != null);
            return "";
        }
        try {
            String answer = chatClient.prompt()
                    .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(SearchRequest.builder()
                                    .topK(defaultTopK)
                                    .similarityThreshold(defaultSimilarityThreshold)
                                    .build())
                            .build())
                    .user(question)
                    .call()
                    .content();
            log.info("[RAG] Answered question via RAG pipeline ({} chars)", answer != null ? answer.length() : 0);
            return answer != null ? answer : "";
        } catch (Exception e) {
            log.error("[RAG] askWithRag failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Ask with RAG, scoped to SOP documents only.
     */
    public String askSopRag(String question) {
        if (!isVectorStoreAvailable() || chatClient == null) return "";
        try {
            String answer = chatClient.prompt()
                    .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(SearchRequest.builder()
                                    .topK(defaultTopK)
                                    .similarityThreshold(defaultSimilarityThreshold)
                                    .filterExpression("doc_type == '" + TYPE_SOP + "'")
                                    .build())
                            .build())
                    .user(question)
                    .call()
                    .content();
            return answer != null ? answer : "";
        } catch (Exception e) {
            log.error("[RAG] askSopRag failed: {}", e.getMessage());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOP ingestion helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convenience method — ingest an SOP procedure into the vector store.
     *
     * @param sopId       SOP UUID
     * @param tenantId    Tenant identifier for multi-tenant filtering
     * @param category    SOP category (APPLICATION, DATABASE, PERFORMANCE, etc.)
     * @param title       SOP title
     * @param description Full SOP description / step text
     */
    public boolean ingestSop(String sopId, String tenantId, String category,
                              String title, String description) {
        String content = String.format("SOP: %s\nCategory: %s\nDescription: %s",
                title, category, description);
        Map<String, Object> meta = Map.of(
                "tenant_id", tenantId != null ? tenantId : "",
                "category",  category != null ? category : "",
                "sop_title", title != null ? title : ""
        );
        return ingest(sopId, content, TYPE_SOP, meta);
    }

    /**
     * Convenience method — ingest an incident pattern into the vector store.
     *
     * @param patternId   Pattern UUID
     * @param tenantId    Tenant identifier
     * @param category    Incident category
     * @param description Pattern description / symptoms
     */
    public boolean ingestIncidentPattern(String patternId, String tenantId,
                                          String category, String description) {
        String content = String.format("Incident Pattern — Category: %s\nDescription: %s",
                category, description);
        Map<String, Object> meta = Map.of(
                "tenant_id", tenantId != null ? tenantId : "",
                "category",  category != null ? category : ""
        );
        return ingest(patternId, content, TYPE_PATTERN, meta);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true when the pgvector VectorStore is available and RAG is enabled. */
    public boolean isVectorStoreAvailable() {
        return ragEnabled && vectorStore != null;
    }

    /** Returns true when both ChatClient and VectorStore are configured for full RAG. */
    public boolean isFullRagAvailable() {
        return isVectorStoreAvailable() && chatClient != null;
    }

    /**
     * Returns a human-readable status summary for the /actuator/info endpoint.
     */
    public Map<String, Object> getStatus() {
        return Map.of(
                "rag_enabled",         ragEnabled,
                "vector_store_active", isVectorStoreAvailable(),
                "chat_client_active",  chatClient != null,
                "full_rag_available",  isFullRagAvailable(),
                "spring_ai_version",   "1.0.0"
        );
    }
}
