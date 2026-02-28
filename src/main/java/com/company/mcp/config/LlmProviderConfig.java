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

/**
 * LlmProviderConfig — wires Spring AI 1.0.0 GA ChatClient bean.
 *
 * <h3>Available providers (spring-ai-starter-model-* artifacts)</h3>
 * <table border="1">
 *   <tr><th>Provider</th><th>Maven artifact</th><th>Env vars to activate</th></tr>
 *   <tr><td>OpenAI</td>
 *       <td>spring-ai-starter-model-openai</td>
 *       <td>OPENAI_API_KEY + SPRING_AI_OPENAI_CHAT_ENABLED=true</td></tr>
 *   <tr><td>Ollama (local)</td>
 *       <td>spring-ai-starter-model-ollama</td>
 *       <td>SPRING_AI_OLLAMA_CHAT_ENABLED=true  (no API key needed)</td></tr>
 *   <tr><td>Anthropic / Claude</td>
 *       <td>spring-ai-starter-model-anthropic</td>
 *       <td>ANTHROPIC_API_KEY + SPRING_AI_ANTHROPIC_ENABLED=true</td></tr>
 *   <tr><td>Google Gemini (Vertex AI)</td>
 *       <td>spring-ai-starter-model-vertex-ai-gemini</td>
 *       <td>GOOGLE_APPLICATION_CREDENTIALS + GCP_PROJECT_ID + SPRING_AI_GEMINI_ENABLED=true</td></tr>
 * </table>
 *
 * <h3>RAG (pgvector VectorStore) — spring-ai-starter-vector-store-pgvector</h3>
 * Auto-configures {@code PgVectorStore} when:
 * <ul>
 *   <li>A PostgreSQL datasource is present (always configured)</li>
 *   <li>An {@code EmbeddingModel} bean is active (provider must be enabled)</li>
 * </ul>
 * When no {@code EmbeddingModel} is present, the vector store is skipped and
 * {@link com.company.mcp.service.EmbeddingService} uses deterministic mock vectors.
 *
 * <h3>Quick-start</h3>
 * <pre>
 *   # Option A — OpenAI (cloud)
 *   export OPENAI_API_KEY=sk-...
 *   export SPRING_AI_OPENAI_CHAT_ENABLED=true
 *   export SPRING_AI_OPENAI_EMBED_ENABLED=true
 *
 *   # Option B — Ollama (local, free, no key)
 *   ollama pull llama3.2 && ollama pull nomic-embed-text
 *   export SPRING_AI_OLLAMA_CHAT_ENABLED=true
 *   export SPRING_AI_OLLAMA_EMBED_ENABLED=true
 *
 *   # Option C — Anthropic / Claude
 *   export ANTHROPIC_API_KEY=sk-ant-api03-...
 *   export SPRING_AI_ANTHROPIC_ENABLED=true
 *
 *   # Option D — Google Gemini via Vertex AI
 *   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json
 *   export GCP_PROJECT_ID=my-project
 *   export SPRING_AI_GEMINI_ENABLED=true
 * </pre>
 */
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


/**
 * LlmProviderConfig — wires Spring AI ChatClient and EmbeddingModel beans.
 *
 * <h3>Provider selection</h3>
 * The active provider is determined by which Spring AI starter autoconfigures
 * a {@code ChatClient.Builder}. This happens when one of the following is set:
 * <ul>
 *   <li>{@code OPENAI_API_KEY} + {@code SPRING_AI_OPENAI_CHAT_ENABLED=true}</li>
 *   <li>{@code SPRING_AI_OLLAMA_CHAT_ENABLED=true} (no key needed)</li>
 * </ul>
 *
 * <h3>Fallback</h3>
 * If no provider is configured, the {@code ChatClient} bean is absent.
 * {@link com.company.mcp.service.ScriptGeneratorService} will then fall back
 * to direct-HTTP mode (still OpenAI-compatible) or built-in templates.
 * {@link com.company.mcp.service.EmbeddingService} will use deterministic
 * mock embeddings for development / CI.
 *
 * <h3>Switching provider at runtime</h3>
 * Set environment variables before starting the JVM:
 * <pre>
 *   # OpenAI
 *   OPENAI_API_KEY=sk-...
 *   SPRING_AI_OPENAI_CHAT_ENABLED=true
 *   SPRING_AI_OPENAI_EMBED_ENABLED=true
 *
 *   # Ollama (local)
 *   SPRING_AI_OLLAMA_CHAT_ENABLED=true
 *   SPRING_AI_OLLAMA_EMBED_ENABLED=true
 *   OLLAMA_BASE_URL=http://localhost:11434
 * </pre>
 */
@Slf4j
@Configuration
public class LlmProviderConfig {

    @Value("${mcp.llm.provider:ollama}")
    private String provider;

    @Value("${mcp.script-gen.api-key:}")
    private String directApiKey;

    // ─────────────────────────────────────────────────────────────────────────
    // Spring AI beans  (conditional on starter being active)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a {@link ChatClient} from whichever Spring AI starter is active
     * (OpenAI, Ollama, etc.).  The builder is auto-configured by the starter;
     * if no provider is enabled this bean is simply absent.
     *
     * @param builder Spring AI auto-configured builder
     * @return ready-to-use {@link ChatClient}
     */
    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    public ChatClient springAiChatClient(ChatClient.Builder builder) {
        log.info("[LLM] Spring AI ChatClient created via provider='{}'", provider);
        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Startup diagnostics
    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void logProviderConfig() {
        boolean hasDirectKey = directApiKey != null && !directApiKey.isBlank();
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  LLM PROVIDER CONFIG                                         ║");
        log.info("║  mcp.llm.provider     = {}                                  ", provider);
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  Spring AI starters available: openai, ollama                ║");
        log.info("║  Activation: set SPRING_AI_OPENAI_CHAT_ENABLED=true          ║");
        log.info("║           or SPRING_AI_OLLAMA_CHAT_ENABLED=true              ║");
        if (hasDirectKey) {
            log.info("║  Direct HTTP fallback key:   ***  (mcp.script-gen.api-key)   ║");
        } else {
            log.info("║  Direct HTTP fallback key:   (not set)                       ║");
        }
        log.info("╚══════════════════════════════════════════════════════════════╝");

        if (!hasDirectKey) {
            log.warn("[LLM] No API key configured. ScriptGeneratorService will use "
                    + "Spring AI ChatClient if available, else template fallback.");
        }
    }
}
