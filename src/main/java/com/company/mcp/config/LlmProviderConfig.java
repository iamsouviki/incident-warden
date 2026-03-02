package com.company.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import jakarta.annotation.PostConstruct;


@Slf4j
@Configuration
public class LlmProviderConfig {

    @Value("${mcp.llm.provider:ollama}")
    private String provider;

    @Value("${mcp.script-gen.api-key:}")
    private String directApiKey;

    /** All active EmbeddingModel beans — may be >1 when multiple provider starters are on classpath. */
    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    // ─────────────────────────────────────────────────────────────────────────
    // Spring AI ChatClient bean
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a {@link ChatClient} from whichever Spring AI provider starter is active.
     * The {@link ChatClient.Builder} is auto-configured by the enabled starter.
     * If no provider is enabled, this bean is absent and ScriptGeneratorService
     * falls back to direct-HTTP or built-in templates.
     */
    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    public ChatClient springAiChatClient(ChatClient.Builder builder) {
        log.info("[LLM] Spring AI 1.0.0 GA ChatClient created — provider='{}'", provider);
        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Startup diagnostics
    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void logProviderConfig() {
        boolean hasDirectKey = directApiKey != null && !directApiKey.isBlank();
        boolean hasEmbedModel = embeddingModels != null && !embeddingModels.isEmpty();

        log.info("╔═══════════════════════════════════════════════════════════════╗");
        log.info("║  SPRING AI 1.0.0 GA — LLM PROVIDER CONFIG                    ║");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  mcp.llm.provider     = {}", provider);
        log.info("║  EmbeddingModel       = {} → RAG/pgvector {}",
                hasEmbedModel ? "ACTIVE  " : "INACTIVE",
                hasEmbedModel ? "ENABLED" : "DISABLED (mock embeddings)");
        log.info("║  Direct HTTP fallback = {}", hasDirectKey ? "CONFIGURED (***)" : "not set");
        log.info("╠═══════════════════════════════════════════════════════════════╣");
        log.info("║  To activate a provider, set environment variables:           ║");
        log.info("║  OpenAI   → OPENAI_API_KEY + SPRING_AI_OPENAI_CHAT_ENABLED=true    ║");
        log.info("║  Ollama   → SPRING_AI_OLLAMA_CHAT_ENABLED=true  (no key)           ║");
        log.info("║  Anthropic→ ANTHROPIC_API_KEY + SPRING_AI_ANTHROPIC_ENABLED=true   ║");
        log.info("║  Gemini   → GOOGLE_APPLICATION_CREDENTIALS + GCP_PROJECT_ID        ║");
        log.info("║             + SPRING_AI_GEMINI_ENABLED=true                        ║");
        log.info("╚═══════════════════════════════════════════════════════════════╝");
    }
}
