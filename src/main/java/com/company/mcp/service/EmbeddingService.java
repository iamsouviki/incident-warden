package com.company.mcp.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding Service — provides pgvector-compatible embedding strings for RAG.
 *
 * <h3>Mode selection</h3>
 * <ul>
 *   <li><b>Spring AI mode</b>: When a {@link EmbeddingModel} bean is present
 *       (activated by {@code SPRING_AI_OPENAI_EMBED_ENABLED=true} or
 *       {@code SPRING_AI_OLLAMA_EMBED_ENABLED=true}), calls the LLM provider's
 *       embedding endpoint and returns real semantic vectors.</li>
 *   <li><b>Mock mode</b>: Falls back to a deterministic hash-based 1536D vector
 *       when no {@code EmbeddingModel} is configured (development / CI).</li>
 * </ul>
 */
@Slf4j
@Service
public class EmbeddingService {

    // ── Spring AI EmbeddingModel (optional — null when no provider enabled) ──
    // Use List injection to avoid ambiguity when multiple provider starters are on the classpath.
    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Value("${mcp.llm.provider:ollama}")
    private String provider;

    /** Resolved at startup via @PostConstruct — the single model for the active provider. */
    private EmbeddingModel embeddingModel;

    @PostConstruct
    private void resolveEmbeddingModel() {
        if (embeddingModels == null || embeddingModels.isEmpty()) {
            log.debug("[Embedding] No EmbeddingModel beans found — mock mode active");
            return;
        }
        if (embeddingModels.size() == 1) {
            embeddingModel = embeddingModels.get(0);
            log.info("[Embedding] Single EmbeddingModel resolved: {}",
                    embeddingModel.getClass().getSimpleName());
            return;
        }
        // Multiple embedding models on classpath — pick the one matching mcp.llm.provider
        String preferred = switch (provider.toLowerCase()) {
            case "openai"    -> "openai";
            case "ollama"    -> "ollama";
            default          -> "";
        };
        embeddingModel = embeddingModels.stream()
                .filter(m -> !preferred.isEmpty() &&
                             m.getClass().getSimpleName().toLowerCase().contains(preferred))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("[Embedding] No model matched provider='{}' among {} candidates — "
                            + "using first available", provider, embeddingModels.size());
                    return embeddingModels.get(0);
                });
        log.info("[Embedding] Selected '{}' for provider='{}'",
                embeddingModel.getClass().getSimpleName(), provider);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a pgvector-compatible embedding string for the given text.
     *
     * @param text The text to embed
     * @return pgvector string, e.g. {@code "[0.12,-0.34,...]"}
     */
    public String generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            log.debug("Empty text supplied — returning zero vector");
            return generateZeroVector();
        }

        if (embeddingModel != null) {
            try {
                log.debug("[Embedding] Calling Spring AI EmbeddingModel for text ({} chars)", text.length());
                float[] vector = embeddingModel.embed(text);
                return toPgVector(vector);
            } catch (Exception e) {
                log.warn("[Embedding] Spring AI EmbeddingModel failed ({}), falling back to mock", e.getMessage());
            }
        } else {
            log.debug("[Embedding] No EmbeddingModel configured — using deterministic mock");
        }

        return generateMockEmbedding(text);
    }

    /**
     * Batch embed multiple texts.
     *
     * @param texts List of texts to embed
     * @return List of pgvector strings, same order as input
     */
    public List<String> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(texts.size());
        for (String t : texts) result.add(generateEmbedding(t));
        return result;
    }

    /**
     * Cosine similarity between two pgvector strings.
     * Used for offline scoring / unit tests.
     */
    public double cosineSimilarity(String vector1, String vector2) {
        try {
            double[] v1 = parseVector(vector1);
            double[] v2 = parseVector(vector2);
            if (v1.length != v2.length) return 0.0;

            double dot = 0, norm1 = 0, norm2 = 0;
            for (int i = 0; i < v1.length; i++) {
                dot   += v1[i] * v2[i];
                norm1 += v1[i] * v1[i];
                norm2 += v2[i] * v2[i];
            }
            if (norm1 == 0 || norm2 == 0) return 0.0;
            return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
        } catch (Exception e) {
            log.warn("Failed to compute cosine similarity: {}", e.getMessage());
            return 0.0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Deterministic mock — hash-based 1536D vector for development / CI. */
    private String generateMockEmbedding(String text) {
        int[] vector = new int[1536];
        int hashCode = text.hashCode();
        for (int i = 0; i < 1536; i++) {
            vector[i] = (int) ((Math.sin(i * hashCode) * 10) % 100);
        }
        return toPgVector(vector);
    }

    /** All-zero 1536D vector returned for null/empty input. */
    private String generateZeroVector() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            sb.append("0");
            if (i < 1535) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private static String toPgVector(int[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private static double[] parseVector(String vectorStr) {
        vectorStr = vectorStr.replaceAll("[\\[\\]\\s]", "");
        String[] parts = vectorStr.split(",");
        double[] vector = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Double.parseDouble(parts[i]);
        }
        return vector;
    }
}
