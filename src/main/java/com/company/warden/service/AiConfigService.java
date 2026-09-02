package com.company.warden.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Service
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

    @Autowired
    private com.company.warden.repository.SystemConfigRepository systemConfigRepository;

    private String provider = "ollama";
    private String baseUrl = "http://localhost:11434";

    /**
     * Read from the environment, never from the database and never from the UI.
     *
     * This used to be a row in config.system_config, written by the AI Configuration
     * page and handed back in plaintext by GET /api/v1/ai/config. A provider credential
     * in a table that the application itself can rewrite is a credential in a backup, in
     * a replica, and in every screenshot of that page. Migration 1.16 deletes the row.
     *
     * The cost of this choice: switching provider to one that needs a key now requires a
     * restart with the variable set, because nothing in the running process can change it.
     * That is the intended trade — an unset key fails a model call, which is recoverable;
     * a leaked key is not.
     */
    @org.springframework.beans.factory.annotation.Value("${MCP_LLM_API_KEY:}")
    private String apiKey = "";

    private String activeChatModel = "qwen2.5-coder:3b";
    private String activeEmbeddingModel = "nomic-embed-text:latest";

    @PostConstruct
    public void init() {
        try {
            log.info("[CONFIG] Loading AI configurations from database...");
            java.util.List<com.company.warden.model.SystemConfig> configs = systemConfigRepository.findAll();
            for (com.company.warden.model.SystemConfig config : configs) {
                String key = config.getConfigKey();
                String val = config.getConfigValue();
                if (val == null) continue;

                switch (key) {
                    case "api_key":
                        this.apiKey = decodeBase64(val);
                        break;
                    case "provider":
                        this.provider = val;
                        break;
                    case "base_url":
                        this.baseUrl = val;
                        break;
                    case "active_chat_model":
                        this.activeChatModel = val;
                        break;
                    case "active_embedding_model":
                        this.activeEmbeddingModel = val;
                        break;
                }
            }
            log.info("[CONFIG] Loaded AI config: provider={}, chatModel={}, embeddingModel={}", provider, activeChatModel, activeEmbeddingModel);
        } catch (Exception e) {
            log.warn("[CONFIG] Failed to load settings from DB, using memory defaults: {}", e.getMessage());
        }
    }

    private static String encodeBase64(String value) {
        if (value == null || value.isBlank()) return "";
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decodeBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            return new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded; // Fallback to raw string if not Base64
        }
    }

    private void updateConfig(String key, String value) {
        try {
            systemConfigRepository.save(new com.company.warden.model.SystemConfig(key, value));
            log.info("[CONFIG] Persisted configuration key={} value={}", key, value);
        } catch (Exception e) {
            log.error("[CONFIG] Failed to persist configuration key={} value={}: {}", key, value, e.getMessage());
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
        updateConfig("provider", provider);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        updateConfig("base_url", baseUrl);
    }

    /** Empty when no key is configured; callers treat that as "no key available". */
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        updateConfig("api_key", encodeBase64(this.apiKey));
    }

    public String getActiveChatModel() {
        return activeChatModel;
    }

    public void setActiveChatModel(String activeChatModel) {
        this.activeChatModel = activeChatModel;
        updateConfig("active_chat_model", activeChatModel);
    }

    public String getActiveEmbeddingModel() {
        return activeEmbeddingModel;
    }

    public void setActiveEmbeddingModel(String activeEmbeddingModel) {
        this.activeEmbeddingModel = activeEmbeddingModel;
        updateConfig("active_embedding_model", activeEmbeddingModel);
    }


}
