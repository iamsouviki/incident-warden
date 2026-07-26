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
    private String apiKey = "";
    private String activeChatModel = "qwen2.5-coder:3b";
    private String activeEmbeddingModel = "nomic-embed-text:latest";
    private String autoResolveThreshold = "1.00";
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
                    case "api_key":
                        this.apiKey = val;
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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        updateConfig("api_key", apiKey);
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
