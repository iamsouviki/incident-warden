package com.company.mcp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiConfigService {

    private String activeChatModel;
    private String activeEmbeddingModel;

    public AiConfigService(
            @Value("${OLLAMA_CHAT_MODEL:qwen2.5-coder:latest}") String defaultChatModel,
            @Value("${OLLAMA_EMBED_MODEL:nomic-embed-text}") String defaultEmbeddingModel) {
        this.activeChatModel = defaultChatModel;
        this.activeEmbeddingModel = defaultEmbeddingModel;
    }

    public String getActiveChatModel() {
        return activeChatModel;
    }

    public void setActiveChatModel(String activeChatModel) {
        this.activeChatModel = activeChatModel;
    }

    public String getActiveEmbeddingModel() {
        return activeEmbeddingModel;
    }

    public void setActiveEmbeddingModel(String activeEmbeddingModel) {
        this.activeEmbeddingModel = activeEmbeddingModel;
    }
}
