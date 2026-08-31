package com.company.mcp.controller;

import com.company.mcp.service.AiConfigService;
import com.company.mcp.service.AutoRemediationService;
import com.company.mcp.service.NotificationService;
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
    private final NotificationService notificationService;
    private final AutoRemediationService autoRemediation;

    public AiConfigController(AiConfigService aiConfigService, ObjectMapper objectMapper,
                              NotificationService notificationService,
                              AutoRemediationService autoRemediation) {
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.autoRemediation = autoRemediation;
    }

    @GetMapping
    public ResponseEntity<?> getConfig() {
        // Map.ofEntries, not Map.of: the latter stops at ten pairs and this outgrew it.
        return ResponseEntity.ok(Map.ofEntries(
                // apiKey is deliberately absent. It is an environment variable now, and echoing
                // a credential back to a browser is how it ends up in a screenshot or a log.
                // "apiKeyPresent" tells the page whether a key exists without disclosing it.
                Map.entry("provider", aiConfigService.getProvider()),
                Map.entry("baseUrl", aiConfigService.getBaseUrl()),
                Map.entry("apiKeyPresent", !aiConfigService.getApiKey().isBlank()),
                Map.entry("chatModel", aiConfigService.getActiveChatModel()),
                Map.entry("embeddingModel", aiConfigService.getActiveEmbeddingModel()),
                Map.entry("autoResolveThreshold", aiConfigService.getAutoResolveThreshold()),
                Map.entry("hitlThreshold", aiConfigService.getHitlThreshold()),
                Map.entry("blastRadiusThreshold", aiConfigService.getBlastRadiusThreshold()),
                Map.entry("servicenowEnabled", aiConfigService.getServicenowEnabled()),
                Map.entry("freshserviceEnabled", aiConfigService.getFreshserviceEnabled()),
                Map.entry("webSearchEnabled", aiConfigService.getWebSearchEnabled())));
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
        // An "apiKey" field in the body is ignored rather than rejected: an older client
        // that still sends one gets its other settings saved, and the key goes nowhere.
        String provider = body.get("provider"); String baseUrl = body.get("baseUrl");
        String chatModel = body.get("chatModel"); String embeddingModel = body.get("embeddingModel");
        if (provider == null || provider.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        if (chatModel == null || chatModel.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "chatModel is required"));
        if (embeddingModel == null || embeddingModel.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "embeddingModel is required"));
        aiConfigService.setProvider(provider); if (baseUrl != null) aiConfigService.setBaseUrl(baseUrl);
        aiConfigService.setActiveChatModel(chatModel); aiConfigService.setActiveEmbeddingModel(embeddingModel);
        if (body.get("autoResolveThreshold") != null) aiConfigService.setAutoResolveThreshold(body.get("autoResolveThreshold"));
        if (body.get("hitlThreshold") != null) aiConfigService.setHitlThreshold(body.get("hitlThreshold"));
        if (body.get("blastRadiusThreshold") != null) aiConfigService.setBlastRadiusThreshold(body.get("blastRadiusThreshold"));
        if (body.get("servicenowEnabled") != null) aiConfigService.setServicenowEnabled(body.get("servicenowEnabled"));
        if (body.get("freshserviceEnabled") != null) aiConfigService.setFreshserviceEnabled(body.get("freshserviceEnabled"));
        if (body.get("webSearchEnabled") != null) aiConfigService.setWebSearchEnabled(body.get("webSearchEnabled"));
        return ResponseEntity.ok(Map.of("message", "AI & Platform Configuration updated successfully", "provider", provider, "chatModel", chatModel, "embeddingModel", embeddingModel));
    }

    private String safeMessage(Exception e) { String message = e.getMessage(); return message == null ? e.getClass().getSimpleName() : message.substring(0, Math.min(240, message.length())); }

    // ── Notification transport ──────────────────────────────────────────────────
    // Lives under /api/v1/ai/config/** because that path is already ADMIN-only, and
    // because this is the same "configure the platform from the UI, never from a
    // properties file" surface. No credential is accepted here: the relay is
    // unauthenticated by design (see NotificationService).

    @GetMapping("/notifications")
    public ResponseEntity<?> getNotificationSettings() {
        NotificationService.Settings settings = notificationService.settings();
        return ResponseEntity.ok(Map.of(
                "enabled", settings.enabled(), "host", settings.host(),
                "port", settings.port(), "from", settings.from()));
    }

    @PostMapping("/notifications")
    public ResponseEntity<?> setNotificationSettings(@RequestBody Map<String, String> body) {
        boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "false"));
        String host = body.getOrDefault("host", "").trim();
        String from = body.getOrDefault("from", "").trim();
        int port;
        try {
            port = Integer.parseInt(body.getOrDefault("port", "25").trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "port must be a number"));
        }
        if (port < 1 || port > 65535) return ResponseEntity.badRequest().body(Map.of("error", "port must be between 1 and 65535"));
        // Only enforced when switching on: an admin may save a half-filled form while
        // notifications stay off, but must not be able to enable a relay that cannot work.
        if (enabled && (host.isBlank() || from.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "host and from address are required to enable notifications"));
        }
        notificationService.saveSettings(new NotificationService.Settings(enabled, host, port, from));
        return ResponseEntity.ok(Map.of("message", "Notification settings updated"));
    }

    /**
     * Sends one message to a single address so an admin can tell a working relay from a
     * typo without editing a real incident to find out.
     */
    @PostMapping("/notifications/test")
    public ResponseEntity<?> testNotification(@RequestBody Map<String, String> body) {
        String to = body.getOrDefault("to", "").trim();
        if (to.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "A destination address is required"));
        boolean sent = notificationService.send(List.of(to),
                "Incident automation: test message",
                "This is a test from the incident automation platform. If you received it, "
                        + "notifications are configured correctly.\n");
        return sent
                ? ResponseEntity.ok(Map.of("message", "Test message accepted by the relay."))
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "error", "Nothing was sent. Check that notifications are enabled, the address is valid, and the relay is reachable. Server logs have the reason."));
    }

    /**
     * The unattended-remediation kill switch. Lives here rather than in a properties file
     * because the person who needs to turn autonomy off at 3am is an admin with a browser,
     * not someone who can redeploy.
     */
    @GetMapping("/autorun")
    public ResponseEntity<?> getAutoRun() {
        return ResponseEntity.ok(Map.of("enabled", autoRemediation.enabled()));
    }

    @PostMapping("/autorun")
    public ResponseEntity<?> setAutoRun(@RequestBody Map<String, String> body) {
        boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "false"));
        autoRemediation.setEnabled(enabled);
        return ResponseEntity.ok(Map.of("enabled", enabled, "message", enabled
                ? "Unattended remediation is ON. A new incident that closely matches a resolved one may "
                        + "now repeat that incident's approved read-only or restart tool without waiting for approval."
                : "Unattended remediation is OFF. Every action now waits for a human approval."));
    }
}
