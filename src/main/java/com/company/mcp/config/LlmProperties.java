package com.company.mcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║           ENTERPRISE LLM PROPERTIES                             ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * <p>Controls which LLM backend the platform uses.
 *
 * <h3>Known providers (use Spring AI Maven starters — configure via spring.ai.*)</h3>
 * <pre>
 * mcp.llm.provider: ollama      → spring.ai.ollama.*
 * mcp.llm.provider: openai      → spring.ai.openai.*
 * mcp.llm.provider: anthropic   → spring.ai.anthropic.*
 * mcp.llm.provider: gemini      → spring.ai.vertex.ai.gemini.*
 * </pre>
 *
 * <h3>Custom / other provider (OpenAI-compatible, built from url + key)</h3>
 * <pre>
 * mcp:
 *   llm:
 *     provider: custom
 *     custom:
 *       api-url:     http://my-vllm-server:8000/v1
 *       api-key:     my-secret-key
 *       chat-model:  meta-llama/Llama-3.1-70B-Instruct
 *       embed-model: nomic-embed-text
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mcp.llm")
public class LlmProperties {

    /**
     * Active LLM provider.
     * <ul>
     *   <li>{@code ollama}    — Spring AI Ollama starter (spring.ai.ollama.*)</li>
     *   <li>{@code openai}    — Spring AI OpenAI starter (spring.ai.openai.*)</li>
     *   <li>{@code anthropic} — Spring AI Anthropic starter (spring.ai.anthropic.*)</li>
     *   <li>{@code gemini}    — Spring AI Vertex AI starter (spring.ai.vertex.ai.gemini.*)</li>
     *   <li>{@code custom}    — Any OpenAI-compatible endpoint; set mcp.llm.custom.*</li>
     * </ul>
     */
    private String provider = "ollama";

    /**
     * Settings for the {@code custom} provider.
     * Only used when {@code mcp.llm.provider=custom}.
     * Any OpenAI-compatible endpoint works: LM Studio, vLLM, Azure OpenAI, LocalAI, etc.
     */
    private Custom custom = new Custom();

    @Getter
    @Setter
    public static class Custom {
        /** Base URL of the OpenAI-compatible API. */
        private String apiUrl = "";
        /** Secret API key. Set to any non-empty value for endpoints that don't require auth. */
        private String apiKey = "";
        /** Model name for chat / classification requests. */
        private String chatModel = "";
        /** Model name for embedding generation (pgvector RAG). */
        private String embedModel = "";
    }
}
