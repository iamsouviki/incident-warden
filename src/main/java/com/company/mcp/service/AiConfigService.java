package com.company.mcp.service;

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
    private com.company.mcp.repository.SystemConfigRepository systemConfigRepository;

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
    @org.springframework.beans.factory.annotation.Value("${mcp.confidence.auto-resolve-threshold:1.00}")
    private String autoResolveThreshold = "1.00";
    @org.springframework.beans.factory.annotation.Value("${mcp.confidence.hitl-threshold:0.80}")
    private String hitlThreshold = "0.80";
    private String blastRadiusThreshold = "0.40";
    private String servicenowEnabled = "false";
    private String freshserviceEnabled = "false";

    @PostConstruct
    public void init() {
        try {
            log.info("[CONFIG] Loading AI configurations from database...");
            java.util.List<com.company.mcp.model.SystemConfig> configs = systemConfigRepository.findAll();
            for (com.company.mcp.model.SystemConfig config : configs) {
                String key = config.getConfigKey();
                String val = config.getConfigValue();
                if (val == null) continue;

                switch (key) {
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
                    case "auto_resolve_threshold":
                        this.autoResolveThreshold = val;
                        break;
                    case "hitl_threshold":
                        this.hitlThreshold = val;
                        break;
                    case "blast_radius_threshold":
                        this.blastRadiusThreshold = val;
                        break;
                    case "servicenow_enabled":
                        this.servicenowEnabled = val;
                        break;
                    case "freshservice_enabled":
                        this.freshserviceEnabled = val;
                        break;
                }
            }
            log.info("[CONFIG] Loaded AI config: provider={}, chatModel={}, embeddingModel={}", provider, activeChatModel, activeEmbeddingModel);
        } catch (Exception e) {
            log.warn("[CONFIG] Failed to load settings from DB, using memory defaults: {}", e.getMessage());
        }
    }

    private void updateConfig(String key, String value) {
        try {
            systemConfigRepository.save(new com.company.mcp.model.SystemConfig(key, value));
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

    public String getAutoResolveThreshold() {
        return autoResolveThreshold;
    }

    public void setAutoResolveThreshold(String autoResolveThreshold) {
        this.autoResolveThreshold = autoResolveThreshold;
        updateConfig("auto_resolve_threshold", autoResolveThreshold);
    }

    public String getHitlThreshold() {
        return hitlThreshold;
    }

    public void setHitlThreshold(String hitlThreshold) {
        this.hitlThreshold = hitlThreshold;
        updateConfig("hitl_threshold", hitlThreshold);
    }

    public String getBlastRadiusThreshold() {
        return blastRadiusThreshold;
    }

    public void setBlastRadiusThreshold(String blastRadiusThreshold) {
        this.blastRadiusThreshold = blastRadiusThreshold;
        updateConfig("blast_radius_threshold", blastRadiusThreshold);
    }

    public String getServicenowEnabled() {
        return servicenowEnabled;
    }

    public void setServicenowEnabled(String servicenowEnabled) {
        this.servicenowEnabled = servicenowEnabled;
        updateConfig("servicenow_enabled", servicenowEnabled);
    }

    public String getFreshserviceEnabled() {
        return freshserviceEnabled;
    }

    public void setFreshserviceEnabled(String freshserviceEnabled) {
        this.freshserviceEnabled = freshserviceEnabled;
        updateConfig("freshservice_enabled", freshserviceEnabled);
    }
}
