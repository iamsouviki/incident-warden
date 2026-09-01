package com.company.mcp.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import com.company.mcp.service.AiConfigService;
import java.util.List;

/**
 * Embeddings always go to Ollama, whatever provider the chat model uses.
 *
 * The vector column is declared {@code vector(768)} in migration 1.0 and re-declared the
 * same way in 1.7, so this schema accepts exactly one embedding width. Ollama's
 * nomic-embed-text produces 768; every hosted OpenAI-compatible embedder returns 1536 or
 * more and fails on insert. This class used to follow {@code AiConfigService.getProvider()}
 * for embeddings as well as chat, which meant pointing the chat model at a hosted provider
 * silently took the vector store down with it — ingestion and SOP search both stop, and the
 * only symptom is a 503 from the SOP upload endpoint.
 *
 * The injected Ollama delegate reads its own URL from {@code spring.ai.ollama.base-url},
 * independent of the chat provider's base URL, which is why nothing here needs a URL.
 *
 * ponytail: single embedding provider. To use a hosted embedder, change the dimension in
 * the DDL, re-embed every row, and give the embedding side its own provider setting —
 * mixing widths in one column is not a config change.
 */
public class DynamicEmbeddingModel implements EmbeddingModel {

    private final OllamaEmbeddingModel ollamaDelegate;
    private final AiConfigService aiConfigService;

    public DynamicEmbeddingModel(OllamaEmbeddingModel ollamaDelegate, AiConfigService aiConfigService) {
        this.ollamaDelegate = ollamaDelegate;
        this.aiConfigService = aiConfigService;
    }

    /** The model name stays configurable from the UI; only the provider is fixed. */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        OllamaOptions options = request.getOptions() instanceof OllamaOptions opt
                ? opt
                : OllamaOptions.builder().build();
        options.setModel(aiConfigService.getActiveEmbeddingModel());
        return ollamaDelegate.call(new EmbeddingRequest(request.getInstructions(), options));
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        return this.embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return this.embed(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return this.call(new EmbeddingRequest(texts, null)).getResults().stream()
                .map(result -> result.getOutput()).toList();
    }

    @Override
    public int dimensions() {
        return this.embed("dimensions-test").length;
    }
}
