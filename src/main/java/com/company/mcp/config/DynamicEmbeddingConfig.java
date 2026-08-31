package com.company.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import com.company.mcp.service.AiConfigService;

@Configuration
public class DynamicEmbeddingConfig {

    @Bean
    @Primary
    public EmbeddingModel dynamicEmbeddingModel(
            OllamaEmbeddingModel ollamaEmbeddingModel,
            AiConfigService aiConfigService) {
        return new DynamicEmbeddingModel(ollamaEmbeddingModel, aiConfigService);
    }
}
