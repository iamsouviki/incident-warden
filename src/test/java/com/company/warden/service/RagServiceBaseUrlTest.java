package com.company.warden.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The failure this guards is silent: a base URL with one extra path segment produces a 404
 * from the provider, which surfaces to the operator as "no suggestion could be produced"
 * rather than as a configuration error.
 */
class RagServiceBaseUrlTest {

    @Test
    void theVersionSegmentSpringAiAppendsIsNotDuplicated() {
        // What every provider's docs — and this platform's own provider presets — quote.
        assertEquals("https://api.tokenrouter.com", RagService.openAiBaseUrl("https://api.tokenrouter.com/v1"));
        assertEquals("https://api.openai.com", RagService.openAiBaseUrl("https://api.openai.com/v1"));

        // A provider that mounts the OpenAI surface under a prefix keeps the prefix.
        assertEquals("https://api.groq.com/openai", RagService.openAiBaseUrl("https://api.groq.com/openai/v1"));

        // Already correct, pasted with a trailing slash, or padded — all left usable.
        assertEquals("https://api.tokenrouter.com", RagService.openAiBaseUrl("https://api.tokenrouter.com"));
        assertEquals("https://api.tokenrouter.com", RagService.openAiBaseUrl("  https://api.tokenrouter.com/v1/  "));

        // Only a whole trailing segment counts: /v11 is somebody's real path, not a version.
        assertEquals("https://host.example.com/v11", RagService.openAiBaseUrl("https://host.example.com/v11"));

        assertEquals("", RagService.openAiBaseUrl(null));
    }
}
