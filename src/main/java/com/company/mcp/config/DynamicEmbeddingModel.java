package com.company.mcp.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import com.company.mcp.service.AiConfigService;
import java.util.List;
import java.util.Objects;

public class DynamicEmbeddingModel implements EmbeddingModel {

    private final OllamaEmbeddingModel ollamaDelegate;
    private final AiConfigService aiConfigService;
    private final org.springframework.web.client.RestClient.Builder restClientBuilder;
    
    private EmbeddingModel activeDelegate;
    private String cachedProvider;
    private String cachedBaseUrl;
    private String cachedApiKey;
    private String cachedModel;

    public DynamicEmbeddingModel(
            OllamaEmbeddingModel ollamaDelegate, 
            AiConfigService aiConfigService,
            org.springframework.web.client.RestClient.Builder restClientBuilder) {
        this.ollamaDelegate = ollamaDelegate;
        this.aiConfigService = aiConfigService;
        this.restClientBuilder = restClientBuilder;
    }

    private synchronized EmbeddingModel getOrBuildDelegate() {
        String provider = aiConfigService.getProvider();
        String baseUrl = aiConfigService.getBaseUrl();
        String apiKey = aiConfigService.getApiKey();
        String model = aiConfigService.getActiveEmbeddingModel();

        if (activeDelegate != null 
                && Objects.equals(provider, cachedProvider)
                && Objects.equals(baseUrl, cachedBaseUrl)
                && Objects.equals(apiKey, cachedApiKey)
                && Objects.equals(model, cachedModel)) {
            return activeDelegate;
        }

        if ("ollama".equalsIgnoreCase(provider)) {
            // We can reuse the spring-injected ollamaDelegate, but we intercept the model parameter
            activeDelegate = ollamaDelegate;
        } else {
            // OpenAI, Groq, OpenRouter, Custom compatible
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .restClientBuilder(restClientBuilder)
                    .build();
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .model(model)
                    .build();
            activeDelegate = new OpenAiEmbeddingModel(api, MetadataMode.ALL, options);
        }

        cachedProvider = provider;
        cachedBaseUrl = baseUrl;
        cachedApiKey = apiKey;
        cachedModel = model;

        return activeDelegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingModel delegate = getOrBuildDelegate();
        String activeModel = aiConfigService.getActiveEmbeddingModel();

        if (delegate instanceof OllamaEmbeddingModel ollama) {
            OllamaOptions options;
            if (request.getOptions() instanceof OllamaOptions opt) {
                options = opt;
                options.setModel(activeModel);
            } else {
                options = OllamaOptions.builder().model(activeModel).build();
            }
            EmbeddingRequest newRequest = new EmbeddingRequest(request.getInstructions(), options);
            return ollama.call(newRequest);
        } else if (delegate instanceof OpenAiEmbeddingModel openai) {
            OpenAiEmbeddingOptions options;
            if (request.getOptions() instanceof OpenAiEmbeddingOptions opt) {
                options = opt;
                options.setModel(activeModel);
            } else {
                options = OpenAiEmbeddingOptions.builder().model(activeModel).build();
            }
            EmbeddingRequest newRequest = new EmbeddingRequest(request.getInstructions(), options);
            return openai.call(newRequest);
        }

        return delegate.call(request);
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
        // Build request with generic options, call handles options conversion
        EmbeddingRequest request = new EmbeddingRequest(texts, null);
        EmbeddingResponse response = this.call(request);
        return response.getResults().stream().map(res -> res.getOutput()).toList();
    }

    @Override
    public int dimensions() {
        return this.embed("dimensions-test").length;
    }
}
