package com.company.mcp.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import com.company.mcp.service.AiConfigService;
import java.util.List;

public class DynamicEmbeddingModel implements EmbeddingModel {

    private final OllamaEmbeddingModel delegate;
    private final AiConfigService aiConfigService;

    public DynamicEmbeddingModel(OllamaEmbeddingModel delegate, AiConfigService aiConfigService) {
        this.delegate = delegate;
        this.aiConfigService = aiConfigService;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        String activeModel = aiConfigService.getActiveEmbeddingModel();
        
        OllamaOptions options;
        if (request.getOptions() instanceof OllamaOptions opt) {
            options = opt;
            options.setModel(activeModel);
        } else {
            options = OllamaOptions.builder().model(activeModel).build();
        }

        EmbeddingRequest newRequest = new EmbeddingRequest(request.getInstructions(), options);
        return delegate.call(newRequest);
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
        EmbeddingResponse response = this.call(new EmbeddingRequest(texts, OllamaOptions.builder().build()));
        return response.getResults().stream().map(res -> res.getOutput()).toList();
    }

    @Override
    public int dimensions() {
        return this.embed("dimensions-test").length;
    }
}
