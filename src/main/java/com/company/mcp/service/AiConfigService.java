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
    private JdbcTemplate jdbcTemplate;

    private String provider = "ollama";
    private String baseUrl = "http://localhost:11434";
    private String apiKey = "";
    private String activeChatModel = "qwen2.5-coder:3b";
    private String activeEmbeddingModel = "nomic-embed-text:latest";

    @PostConstruct
    public void init() {
        try {
            log.info("[CONFIG] Loading AI configurations from database...");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT config_key, config_value FROM system_config");
            for (Map<String, Object> row : rows) {
                String key = (String) row.get("config_key");
                String val = (String) row.get("config_value");
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
                }
            }
            log.info("[CONFIG] Loaded AI config: provider={}, chatModel={}, embeddingModel={}", provider, activeChatModel, activeEmbeddingModel);
        } catch (Exception e) {
            log.warn("[CONFIG] Failed to load settings from DB, using memory defaults: {}", e.getMessage());
        }
    }

    private void updateConfig(String key, String value) {
        try {
            jdbcTemplate.update(
                "INSERT INTO system_config (config_key, config_value) VALUES (?, ?) " +
                "ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value",
                key, value
            );
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
}
