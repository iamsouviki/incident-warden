package com.company.mcp.controller;

import com.company.mcp.service.AiConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/config")
public class AiConfigController {

    private final AiConfigService aiConfigService;

    public AiConfigController(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @GetMapping
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(Map.of(
                "provider", aiConfigService.getProvider(),
                "baseUrl", aiConfigService.getBaseUrl(),
                "apiKey", aiConfigService.getApiKey(),
                "chatModel", aiConfigService.getActiveChatModel(),
                "embeddingModel", aiConfigService.getActiveEmbeddingModel(),
                "autoResolveThreshold", aiConfigService.getAutoResolveThreshold(),
                "hitlThreshold", aiConfigService.getHitlThreshold(),
                "blastRadiusThreshold", aiConfigService.getBlastRadiusThreshold(),
                "servicenowEnabled", aiConfigService.getServicenowEnabled(),
                "freshserviceEnabled", aiConfigService.getFreshserviceEnabled()
        ));
    }

    @GetMapping("/ollama-models")
    public ResponseEntity<?> getOllamaModels(@RequestParam(value = "url", defaultValue = "http://localhost:11434") String url) {
        try {
            org.springframework.ai.ollama.api.OllamaApi api = org.springframework.ai.ollama.api.OllamaApi.builder()
                    .baseUrl(url)
                    .build();
            var response = api.listModels();
            java.util.List<String> models = response.models().stream()
                    .map(m -> m.name())
                    .toList();
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            return ResponseEntity.ok(java.util.List.of(
                    "qwen2.5-coder:latest",
                    "qwen2.5-coder:14b",
                    "qwen2.5-coder:3b",
                    "nomic-embed-text:latest",
                    "qwen3.5:9b",
                    "gemma4:latest"
            ));
        }
    }

    @PostMapping
    public ResponseEntity<?> setConfig(@RequestBody Map<String, String> body) {
        String provider = body.get("provider");
        String baseUrl = body.get("baseUrl");
        String apiKey = body.getOrDefault("apiKey", "");
        String chatModel = body.get("chatModel");
        String embeddingModel = body.get("embeddingModel");
        String autoResolveThreshold = body.get("autoResolveThreshold");
        String hitlThreshold = body.get("hitlThreshold");
        String blastRadiusThreshold = body.get("blastRadiusThreshold");
        String servicenowEnabled = body.get("servicenowEnabled");
        String freshserviceEnabled = body.get("freshserviceEnabled");

        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        if (chatModel == null || chatModel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "chatModel is required"));
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "embeddingModel is required"));
        }

        aiConfigService.setProvider(provider);
        if (baseUrl != null) {
            aiConfigService.setBaseUrl(baseUrl);
        }
        aiConfigService.setApiKey(apiKey);
        aiConfigService.setActiveChatModel(chatModel);
        aiConfigService.setActiveEmbeddingModel(embeddingModel);
        
        if (autoResolveThreshold != null) aiConfigService.setAutoResolveThreshold(autoResolveThreshold);
        if (hitlThreshold != null) aiConfigService.setHitlThreshold(hitlThreshold);
        if (blastRadiusThreshold != null) aiConfigService.setBlastRadiusThreshold(blastRadiusThreshold);
        if (servicenowEnabled != null) aiConfigService.setServicenowEnabled(servicenowEnabled);
        if (freshserviceEnabled != null) aiConfigService.setFreshserviceEnabled(freshserviceEnabled);

        return ResponseEntity.ok(Map.of(
                "message", "AI & Platform Configuration updated successfully",
                "provider", provider,
                "chatModel", chatModel,
                "embeddingModel", embeddingModel
        ));
    }
}
