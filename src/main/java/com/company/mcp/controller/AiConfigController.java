package com.company.mcp.controller;

import com.company.mcp.service.AiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/config")
public class AiConfigController {

    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper;

    public AiConfigController(AiConfigService aiConfigService, ObjectMapper objectMapper) {
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(Map.of(
                "provider", aiConfigService.getProvider(), "baseUrl", aiConfigService.getBaseUrl(), "apiKey", aiConfigService.getApiKey(),
                "chatModel", aiConfigService.getActiveChatModel(), "embeddingModel", aiConfigService.getActiveEmbeddingModel(),
                "autoResolveThreshold", aiConfigService.getAutoResolveThreshold(), "hitlThreshold", aiConfigService.getHitlThreshold(),
                "blastRadiusThreshold", aiConfigService.getBlastRadiusThreshold(), "servicenowEnabled", aiConfigService.getServicenowEnabled(),
                "freshserviceEnabled", aiConfigService.getFreshserviceEnabled()));
    }

    @GetMapping("/ollama-models")
    public ResponseEntity<?> getOllamaModels(@RequestParam(value = "url", required = false) String requestedUrl) {
        String baseUrl = requestedUrl == null || requestedUrl.isBlank() ? aiConfigService.getBaseUrl() : requestedUrl.trim();
        try {
            URI uri = URI.create(baseUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Ollama URL must use HTTP or HTTPS");
            if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("Ollama URL must include a host");
            URL tagsUrl = uri.resolve(uri.getPath().endsWith("/") ? "api/tags" : "/api/tags").toURL();
            HttpURLConnection connection = (HttpURLConnection) tagsUrl.openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("Ollama returned HTTP " + status);
            byte[] bytes = connection.getInputStream().readAllBytes();
            JsonNode root = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            List<String> models = root.path("models").findValuesAsText("name");
            if (models.isEmpty()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Ollama responded but no installed models were found.", "models", List.of()));
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Ollama is unreachable or returned an invalid model list: " + safeMessage(e), "models", List.of()));
        }
    }

    @PostMapping
    public ResponseEntity<?> setConfig(@RequestBody Map<String, String> body) {
        String provider = body.get("provider"); String baseUrl = body.get("baseUrl"); String apiKey = body.getOrDefault("apiKey", "");
        String chatModel = body.get("chatModel"); String embeddingModel = body.get("embeddingModel");
        if (provider == null || provider.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        if (chatModel == null || chatModel.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "chatModel is required"));
        if (embeddingModel == null || embeddingModel.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "embeddingModel is required"));
        aiConfigService.setProvider(provider); if (baseUrl != null) aiConfigService.setBaseUrl(baseUrl); aiConfigService.setApiKey(apiKey);
        aiConfigService.setActiveChatModel(chatModel); aiConfigService.setActiveEmbeddingModel(embeddingModel);
        if (body.get("autoResolveThreshold") != null) aiConfigService.setAutoResolveThreshold(body.get("autoResolveThreshold"));
        if (body.get("hitlThreshold") != null) aiConfigService.setHitlThreshold(body.get("hitlThreshold"));
        if (body.get("blastRadiusThreshold") != null) aiConfigService.setBlastRadiusThreshold(body.get("blastRadiusThreshold"));
        if (body.get("servicenowEnabled") != null) aiConfigService.setServicenowEnabled(body.get("servicenowEnabled"));
        if (body.get("freshserviceEnabled") != null) aiConfigService.setFreshserviceEnabled(body.get("freshserviceEnabled"));
        return ResponseEntity.ok(Map.of("message", "AI & Platform Configuration updated successfully", "provider", provider, "chatModel", chatModel, "embeddingModel", embeddingModel));
    }

    private String safeMessage(Exception e) { String message = e.getMessage(); return message == null ? e.getClass().getSimpleName() : message.substring(0, Math.min(240, message.length())); }
}
