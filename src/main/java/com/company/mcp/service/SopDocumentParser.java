package com.company.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.company.mcp.model.SopScriptRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * SopDocumentParser — extracts SOP fields from uploaded documents using a
 * provider-agnostic 3-tier extraction strategy with chunked processing
 * for large documents.
 *
 * NO category field. NO timeout on LLM calls. Full document passed.
 * Chunks used when document exceeds chunkSize.
 */
@Slf4j
@Service
public class SopDocumentParser {

    @Autowired(required = false)
    private ChatClient chatClient;

    @Autowired(required = false)
    private ScriptGeneratorService scriptGeneratorService;

    @Value("${mcp.llm.provider:ollama}")
    private String llmProvider;

    @Value("${mcp.script-gen.api-url:}")
    private String directApiUrl;

    @Value("${mcp.script-gen.api-key:}")
    private String directApiKey;

    @Value("${mcp.script-gen.model:}")
    private String directModel;

    @Value("${mcp.sop-parser.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${mcp.sop-parser.cache.ttl-minutes:120}")
    private long cacheTtlMinutes;

    @Value("${mcp.sop-parser.cache.max-entries:500}")
    private int cacheMaxEntries;

    @Value("${mcp.sop-parser.llm.chunk-size:4000}")
    private int chunkSize;

    @Value("${mcp.sop-parser.llm.chunk-overlap:500}")
    private int chunkOverlap;

    @Value("${mcp.sop-parser.llm.max-output-tokens:2048}")
    private int llmMaxOutputTokens;

    @Value("${mcp.sop-parser.llm.num-ctx:32768}")
    private int numCtx;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HttpClient with NO connect timeout — supports low-spec devices. */
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    private final Map<String, CacheEntry> parseCache = new ConcurrentHashMap<>();

    private record CacheEntry(ParsedSop parsedSop, long createdAtMillis) {}

    // ── Result DTO — NO category ─────────────────────────────────────────────

    public record ParsedSop(
            String title,
            String description,
            String resolutionSteps,
            String mcpToolScript,
            String rawText,
            String sourceFileName,
            List<String> warnings
    ) {}

    // ── LLM prompts — NO category ────────────────────────────────────────────

    private static final String SOP_EXTRACTION_SYSTEM = """
            You are a structured-data extraction engine. You parse IT SOP documents
            and return ONLY a single JSON object. Never add explanation or markdown.

            Extract these fields:

            {
              "title": "short SOP title (max 120 chars)",
              "description": "2-3 sentence summary of the problem and when this SOP applies",
              "resolutionSteps": "numbered remediation workflow only (actionable steps and commands)",
              "mcpToolScript": "null unless the source document already contains an explicit executable script block"
            }

            RULES:
            - Return ONLY the JSON object. No text before or after.
            - Use null for any field you cannot determine.
            - resolutionSteps: include only actionable remediation steps; skip legal/warning/footer text.
            - Preserve commands exactly when present.
            - Prefer concise output to avoid truncation.
            - mcpToolScript: do NOT generate a new large script. Use null when no script is explicitly present.
            """;

    private static final String SOP_CHUNK_SYSTEM = """
            You are a structured-data extraction engine. You are given a CHUNK of an
            IT SOP document (not the full document). Extract whatever information is
            present in this chunk. Return ONLY a JSON object.

            {
              "title": "short SOP title if present in this chunk, else null",
              "description": "any problem description found in this chunk, else null",
              "resolutionSteps": "any remediation steps / commands found in this chunk, else null",
              "mcpToolScript": "script block found in this chunk, else null"
            }

            RULES:
            - Return ONLY the JSON object. No text before or after.
            - Use null for fields not present in this chunk.
            - Preserve commands exactly when present.
            - Keep output concise.
            """;

    private static final String SOP_MERGE_SYSTEM = """
            You are given multiple partial extraction results from chunks of the same
            SOP document. Merge them into a single coherent result. Return ONLY JSON.

            {
              "title": "the best / most complete title from all chunks",
              "description": "merged description combining information from all chunks. 2-4 sentences.",
              "resolutionSteps": "complete numbered remediation steps, merged and de-duplicated in correct order, preserving all commands",
              "mcpToolScript": "null unless chunks already include an explicit executable script"
            }

            RULES:
            - Return ONLY the JSON object. No text before or after.
            - De-duplicate overlapping steps. Keep the order logical.
            - Keep output concise and avoid repeating long non-remediation sections.
            """;

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    public ParsedSop parseRawText(String content, String fileName) {
        if (content == null || content.isBlank()) {
            return new ParsedSop("Untitled SOP", null, null, null, "",
                    fileName, List.of("Content was empty"));
        }
        log.info("[SopParser] Parsing raw text content ({} chars), hint filename='{}'",
                content.length(), fileName);
        return extractWithLlmFallback(content, fileName);
    }

    public ParsedSop parse(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";
        String rawText;
        try (InputStream is = file.getInputStream()) {
            if (fileName.endsWith(".pdf")) {
                rawText = extractPdf(is);
            } else if (fileName.endsWith(".docx")) {
                rawText = extractDocx(is);
            } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                rawText = extractExcel(is, fileName.endsWith(".xls"));
            } else {
                rawText = new String(file.getBytes());
            }
        }
        log.info("[SopParser] Extracted {} chars from file '{}' (in-memory)",
                rawText.length(), fileName);
        return extractWithLlmFallback(rawText, file.getOriginalFilename());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3-TIER EXTRACTION — full doc, chunked if large, no timeout
    // ═════════════════════════════════════════════════════════════════════════

    private ParsedSop extractWithLlmFallback(String rawText, String fileName) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        String cacheKey = buildCacheKey(rawText, fileName);
        ParsedSop cached = getCached(cacheKey, requestId);
        if (cached != null) return cached;

        log.info("[SopParser][{}] START file='{}' rawChars={} chunkSize={} cacheEnabled={} cacheSize={}",
                requestId, fileName, rawText.length(), chunkSize, cacheEnabled, parseCache.size());

        // Tier 1: Spring AI ChatClient
        if (chatClient != null) {
            ParsedSop result = extractViaChatClient(rawText, fileName, requestId);
            if (result != null) { putCache(cacheKey, result, requestId); return result; }
        } else {
            log.info("[SopParser][{}] Tier-1 SKIPPED (ChatClient bean unavailable)", requestId);
        }

        // Tier 2: Direct HTTP (chunked if needed, NO timeout)
        // Only requires api-url — Ollama does NOT need an API key
        if (directApiUrl != null && !directApiUrl.isBlank()) {
            ParsedSop result = extractViaDirectHttp(rawText, fileName, requestId);
            if (result != null) { putCache(cacheKey, result, requestId); return result; }
        } else {
            log.info("[SopParser][{}] Tier-2 SKIPPED (api-url is not configured)", requestId);
        }

        // Tier 3: Regex
        log.info("[SopParser][{}] Tier-3 START regex heuristic extraction", requestId);
        ParsedSop regexResult = extractFieldsRegex(rawText, fileName);
        putCache(cacheKey, regexResult, requestId);
        log.info("[SopParser][{}] COMPLETE via Tier-3 title='{}'", requestId, regexResult.title());
        return regexResult;
    }

    // ── Tier 1: ChatClient ───────────────────────────────────────────────────

    private ParsedSop extractViaChatClient(String rawText, String fileName, String requestId) {
        try {
            log.info("[SopParser][{}] Tier-1 START provider={} totalChars={}",
                    requestId, llmProvider, rawText.length());

            // ALWAYS try single call first — minimizes LLM calls
            ParsedSop singleResult = singleCallChatClient(rawText, fileName, requestId);
            if (singleResult != null) return singleResult;

            // Single call failed — chunk only if document is large enough
            List<String> chunks = splitIntoChunks(rawText);
            if (chunks.size() <= 1) {
                log.warn("[SopParser][{}] Tier-1 single call failed and doc too small to chunk", requestId);
                return null;
            }

            log.info("[SopParser][{}] Tier-1 SINGLE-CALL FAILED, falling back to {} chunks", requestId, chunks.size());

            List<String> chunkResults = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("[SopParser][{}] Tier-1 CHUNK {}/{} chars={}", requestId, i+1, chunks.size(), chunk.length());
                try {
                    String resp = chatClient.prompt()
                            .system(SOP_CHUNK_SYSTEM)
                            .user("Extract fields from this SOP chunk:\n\n" + chunk)
                            .call().content();
                    if (resp != null && !resp.isBlank()) {
                        chunkResults.add(resp);
                        log.info("[SopParser][{}] Tier-1 CHUNK {}/{} response chars={}", requestId, i+1, chunks.size(), resp.length());
                    }
                } catch (Exception e) {
                    log.warn("[SopParser][{}] Tier-1 CHUNK {}/{} ERROR: {}", requestId, i+1, chunks.size(), e.getMessage());
                }
            }
            if (chunkResults.isEmpty()) { log.warn("[SopParser][{}] Tier-1 all chunks failed", requestId); return null; }
            return mergeChunkResultsViaChatClient(chunkResults, rawText, fileName, requestId);
        } catch (Exception e) {
            log.warn("[SopParser][{}] Tier-1 ERROR: {}", requestId, e.getMessage());
            return null;
        }
    }

    private ParsedSop singleCallChatClient(String rawText, String fileName, String requestId) {
        try {
            String prompt = "Parse this SOP document and extract structured fields:\n\n" + rawText;
            log.info("[SopParser][{}] Tier-1 SINGLE-CALL inputChars={}", requestId, rawText.length());
            long start = System.currentTimeMillis();
            String llmResponse = chatClient.prompt()
                    .system(SOP_EXTRACTION_SYSTEM).user(prompt).call().content();
            long ms = System.currentTimeMillis() - start;
            log.info("[SopParser][{}] Tier-1 RESPONSE durationMs={} responseChars={}", requestId, ms, llmResponse != null ? llmResponse.length() : 0);
            if (llmResponse != null) log.info("[SopParser][{}] Tier-1 RAW RESPONSE payload={}", requestId, llmResponse);
            if (llmResponse != null && !llmResponse.isBlank()) {
                ParsedSop result = parseLlmResponse(llmResponse, rawText, fileName, llmProvider, requestId);
                if (result != null) { log.info("[SopParser][{}] Tier-1 SUCCESS title='{}'", requestId, result.title()); return result; }
            }
            log.warn("[SopParser][{}] Tier-1 PARSE-FAILED", requestId);
        } catch (Exception e) {
            log.warn("[SopParser][{}] Tier-1 SINGLE-CALL ERROR: {}", requestId, e.getMessage());
        }
        return null;
    }

    private ParsedSop mergeChunkResultsViaChatClient(List<String> chunkResults, String rawText,
                                                      String fileName, String requestId) {
        try {
            String merged = String.join("\n---CHUNK_SEPARATOR---\n", chunkResults);
            log.info("[SopParser][{}] Tier-1 MERGE chunks={} mergedChars={}", requestId, chunkResults.size(), merged.length());
            String resp = chatClient.prompt().system(SOP_MERGE_SYSTEM)
                    .user("Merge these partial SOP extractions into one:\n\n" + merged).call().content();
            if (resp != null && !resp.isBlank()) {
                ParsedSop result = parseLlmResponse(resp, rawText, fileName, llmProvider + "-chunked", requestId);
                if (result != null) { log.info("[SopParser][{}] Tier-1 CHUNKED SUCCESS title='{}'", requestId, result.title()); return result; }
            }
        } catch (Exception e) {
            log.warn("[SopParser][{}] Tier-1 MERGE ERROR: {}", requestId, e.getMessage());
        }
        return mergeChunkResultsLocally(chunkResults, rawText, fileName, requestId, llmProvider);
    }

    // ── Tier 2: Direct HTTP — NO timeout ─────────────────────────────────────

    private ParsedSop extractViaDirectHttp(String rawText, String fileName, String requestId) {
        try {
            log.info("[SopParser][{}] Tier-2 START endpoint={} model={} NO-TIMEOUT totalChars={}",
                    requestId, directApiUrl, directModel, rawText.length());

            // ALWAYS try single call first — minimizes LLM calls
            ParsedSop singleResult = singleCallDirectHttp(rawText, fileName, requestId);
            if (singleResult != null) return singleResult;

            // Single call failed — chunk only if document is large enough
            List<String> chunks = splitIntoChunks(rawText);
            if (chunks.size() <= 1) {
                log.warn("[SopParser][{}] Tier-2 single call failed and doc too small to chunk", requestId);
                return null;
            }

            log.info("[SopParser][{}] Tier-2 SINGLE-CALL FAILED, falling back to {} chunks (chunkSize={})",
                    requestId, chunks.size(), chunkSize);

            List<String> chunkResults = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                log.info("[SopParser][{}] Tier-2 CHUNK {}/{} chars={}", requestId, i+1, chunks.size(), chunk.length());
                String resp = callDirectHttp(requestId, SOP_CHUNK_SYSTEM,
                        "Extract fields from this SOP chunk:\n\n" + chunk, i+1, chunks.size());
                if (resp != null && !resp.isBlank()) chunkResults.add(resp);
            }

            if (chunkResults.isEmpty()) {
                log.warn("[SopParser][{}] Tier-2 all chunks failed", requestId);
                return null;
            }
            return mergeChunkResultsViaHttp(chunkResults, rawText, fileName, requestId);
        } catch (Exception e) {
            log.warn("[SopParser][{}] Tier-2 ERROR: {}", requestId, e.getMessage());
            return null;
        }
    }

    private ParsedSop singleCallDirectHttp(String rawText, String fileName, String requestId) {
        String userPrompt = "Parse this SOP document and extract structured fields:\n\n" + rawText;
        log.info("[SopParser][{}] Tier-2 SINGLE-CALL inputChars={}", requestId, rawText.length());
        long start = System.currentTimeMillis();
        String llmResponse = callDirectHttp(requestId, SOP_EXTRACTION_SYSTEM, userPrompt, 1, 1);
        long ms = System.currentTimeMillis() - start;
        log.info("[SopParser][{}] Tier-2 SINGLE-CALL durationMs={} responseChars={}", requestId, ms, llmResponse != null ? llmResponse.length() : 0);
        if (llmResponse != null && !llmResponse.isBlank()) {
            String provider = "direct-http(" + directModel + ")";
            ParsedSop result = parseLlmResponse(llmResponse, rawText, fileName, provider, requestId);
            if (result != null) { log.info("[SopParser][{}] Tier-2 SUCCESS title='{}'", requestId, result.title()); return result; }
        }
        log.warn("[SopParser][{}] Tier-2 SINGLE-CALL PARSE-FAILED", requestId);
        return null;
    }

    private ParsedSop mergeChunkResultsViaHttp(List<String> chunkResults, String rawText,
                                                String fileName, String requestId) {
        try {
            String merged = String.join("\n---CHUNK_SEPARATOR---\n", chunkResults);
            log.info("[SopParser][{}] Tier-2 MERGE chunks={} mergedChars={}", requestId, chunkResults.size(), merged.length());
            String resp = callDirectHttp(requestId, SOP_MERGE_SYSTEM,
                    "Merge these partial SOP extractions into one:\n\n" + merged, 0, 0);
            if (resp != null && !resp.isBlank()) {
                String provider = "direct-http(" + directModel + ")-chunked";
                ParsedSop result = parseLlmResponse(resp, rawText, fileName, provider, requestId);
                if (result != null) { log.info("[SopParser][{}] Tier-2 CHUNKED SUCCESS title='{}'", requestId, result.title()); return result; }
            }
        } catch (Exception e) {
            log.warn("[SopParser][{}] Tier-2 MERGE ERROR: {}", requestId, e.getMessage());
        }
        return mergeChunkResultsLocally(chunkResults, rawText, fileName, requestId, "direct-http(" + directModel + ")");
    }

    // ── Direct HTTP call — NO timeout ────────────────────────────────────────

    private String callDirectHttp(String requestId, String systemPrompt,
                                   String userPrompt, int chunkNum, int totalChunks) {
        try {
            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("model", directModel != null ? directModel : "default");
            requestMap.put("temperature", 0.0);
            requestMap.put("max_tokens", llmMaxOutputTokens);
            requestMap.put("options", Map.of("num_ctx", numCtx));
            requestMap.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            String requestBody = objectMapper.writeValueAsString(requestMap);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(directApiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            // Only add Authorization header if API key is provided (Ollama doesn't need it)
            if (directApiKey != null && !directApiKey.isBlank() && !"ollama".equalsIgnoreCase(directApiKey)) {
                reqBuilder.header("Authorization", "Bearer " + directApiKey);
            }
            HttpRequest request = reqBuilder.build();

            log.info("[SopParser][{}] Tier-2 HTTP REQUEST bytes={} endpoint={} chunk={}/{} NO-TIMEOUT",
                    requestId, requestBody.length(), directApiUrl, chunkNum, totalChunks);
            log.info("[SopParser][{}] Tier-2 QUERY chunk={}/{} payload={}", requestId, chunkNum, totalChunks, requestBody);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("[SopParser][{}] Tier-2 HTTP STATUS={} bodyChars={} chunk={}/{}",
                    requestId, response.statusCode(), response.body() != null ? response.body().length() : 0, chunkNum, totalChunks);
            if (response.body() != null) {
                log.info("[SopParser][{}] Tier-2 RAW RESPONSE chunk={}/{} payload={}",
                        requestId, chunkNum, totalChunks, response.body());
            }

            if (response.statusCode() != 200) {
                log.warn("[SopParser][{}] Direct HTTP returned status {}", requestId, response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode msg = choices.get(0).get("message");
                if (msg != null && msg.has("content")) {
                    return msg.get("content").asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[SopParser][{}] Tier-2 HTTP ERROR chunk={}/{} message={}",
                    requestId, chunkNum, totalChunks, e.getMessage());
            return null;
        }
    }

    // ── Chunking ─────────────────────────────────────────────────────────────

    private List<String> splitIntoChunks(String text) {
        if (text == null || text.length() <= chunkSize) {
            return List.of(text != null ? text : "");
        }
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            if (end < text.length()) {
                int paraBreak = text.lastIndexOf("\n\n", end);
                if (paraBreak > pos + chunkSize / 2) {
                    end = paraBreak + 2;
                } else {
                    int lineBreak = text.lastIndexOf('\n', end);
                    if (lineBreak > pos + chunkSize / 2) end = lineBreak + 1;
                }
            }
            chunks.add(text.substring(pos, end));
            pos = Math.max(pos + 1, end - chunkOverlap);
        }
        log.info("[SopParser] Split {} chars into {} chunks (chunkSize={} overlap={})",
                text.length(), chunks.size(), chunkSize, chunkOverlap);
        return chunks;
    }

    private ParsedSop mergeChunkResultsLocally(List<String> chunkResults, String rawText,
                                                String fileName, String requestId, String provider) {
        log.info("[SopParser][{}] LOCAL-MERGE of {} chunk results", requestId, chunkResults.size());
        String mergedTitle = null;
        StringBuilder mergedDesc = new StringBuilder();
        StringBuilder mergedSteps = new StringBuilder();
        StringBuilder mergedScript = new StringBuilder();
        for (String chunkJson : chunkResults) {
            try {
                String cleaned = chunkJson.replaceAll("(?im)^\\s*```(?:json)?\\s*$", "").trim();
                int start = cleaned.indexOf('{');
                int end = cleaned.lastIndexOf('}');
                if (start < 0 || end <= start) continue;
                cleaned = cleaned.substring(start, end + 1).replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
                JsonNode json = objectMapper.readTree(cleaned);
                if (mergedTitle == null) { String t = getJsonText(json, "title"); if (t != null) mergedTitle = t; }
                String d = getJsonText(json, "description");
                if (d != null && !d.isBlank()) { if (mergedDesc.length() > 0) mergedDesc.append(" "); mergedDesc.append(d); }
                String s = getJsonText(json, "resolutionSteps");
                if (s != null && !s.isBlank()) { if (mergedSteps.length() > 0) mergedSteps.append("\n"); mergedSteps.append(s); }
                String sc = getJsonText(json, "mcpToolScript");
                if (sc != null && !sc.isBlank()) { if (mergedScript.length() > 0) mergedScript.append("\n"); mergedScript.append(sc); }
            } catch (Exception e) {
                log.warn("[SopParser][{}] LOCAL-MERGE chunk parse error: {}", requestId, e.getMessage());
            }
        }
        if (mergedTitle == null) mergedTitle = "Untitled SOP";
        String finalScript = mergedScript.length() > 0 ? mergedScript.toString()
                : generateMcpScript(mergedTitle, mergedSteps.toString());
        List<String> warnings = new ArrayList<>();
        warnings.add("Extracted using LLM (" + provider + ") with local chunk merge");
        log.info("[SopParser][{}] LOCAL-MERGE SUCCESS title='{}'", requestId, mergedTitle);
        return new ParsedSop(mergedTitle, mergedDesc.toString().trim(),
                mergedSteps.toString().trim(), finalScript, rawText, fileName, warnings);
    }

    // ── LLM response parser — no category ────────────────────────────────────

    private ParsedSop parseLlmResponse(String llmResponse, String rawText,
                                        String fileName, String providerName, String requestId) {
        log.info("[SopParser][{}] PARSE-LLM START provider={} rawResponseChars={}",
                requestId, providerName, llmResponse.length());

        String cleaned = llmResponse.replaceAll("(?im)^\\s*```(?:json)?\\s*$", "").trim();
        int start = cleaned.indexOf('{');
        if (start < 0) {
            log.warn("[SopParser][{}] PARSE-LLM NO-JSON provider={}", requestId, providerName);
            return null;
        }

        int end = cleaned.lastIndexOf('}');
        if (end > start) {
            try {
                String strictJson = cleaned.substring(start, end + 1)
                        .replaceAll(",\\s*}", "}")
                        .replaceAll(",\\s*]", "]");
                JsonNode json = objectMapper.readTree(strictJson);
                return buildParsedSopFromJson(json, rawText, fileName, providerName, requestId);
            } catch (Exception e) {
                log.warn("[SopParser][{}] PARSE-LLM STRICT-JSON ERROR provider={} message={}",
                        requestId, providerName, e.getMessage());
            }
        } else {
            log.warn("[SopParser][{}] PARSE-LLM JSON appears truncated (missing closing brace) provider={}",
                    requestId, providerName);
        }

        ParsedSop recovered = recoverFromPartialLlmJson(cleaned.substring(start), rawText, fileName, providerName, requestId);
        if (recovered != null) {
            return recovered;
        }

        log.warn("[SopParser][{}] PARSE-LLM RECOVERY FAILED provider={}", requestId, providerName);
        return null;
    }

    private ParsedSop buildParsedSopFromJson(JsonNode json, String rawText, String fileName,
                                             String providerName, String requestId) {
        String title         = getJsonText(json, "title");
        String description   = getJsonText(json, "description");
        String steps         = getJsonText(json, "resolutionSteps");
        String mcpToolScript = getJsonText(json, "mcpToolScript");

        StringBuilder enrichedDesc = new StringBuilder();
        if (description != null) enrichedDesc.append(description);
        String severity = getJsonText(json, "severity");
        String service  = getJsonText(json, "affectedService");
        String team     = getJsonText(json, "ownerTeam");
        if (severity != null) enrichedDesc.append("\n\nSeverity: ").append(severity);
        if (service != null) enrichedDesc.append("\nAffected Service: ").append(service);
        if (team != null) enrichedDesc.append("\nOwner Team: ").append(team);

        String mcpActions = getJsonText(json, "mcpActions");
        String enrichedSteps = steps;
        if (mcpActions != null && !mcpActions.isBlank() && !"null".equals(mcpActions)) {
            enrichedSteps = (steps != null ? steps : "") + "\n\n--- MCP Automation Actions ---\n" + mcpActions;
        }

        if (mcpToolScript == null || mcpToolScript.isBlank()) {
            mcpToolScript = generateMcpScript(title != null ? title : "Untitled SOP", steps);
            log.info("[SopParser][{}] PARSE-LLM mcpToolScript missing -> generated locally", requestId);
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("Extracted using LLM (" + providerName + ")");
        if (title == null) {
            title = "Untitled SOP";
            warnings.add("LLM could not determine title");
        }

        log.info("[SopParser][{}] PARSE-LLM SUCCESS provider={} titlePresent={} descPresent={} stepsPresent={} scriptPresent={}",
                requestId, providerName, title != null, enrichedDesc.length() > 0,
                enrichedSteps != null && !enrichedSteps.isBlank(),
                mcpToolScript != null && !mcpToolScript.isBlank());

        return new ParsedSop(title, enrichedDesc.toString().trim(),
                enrichedSteps, mcpToolScript, rawText, fileName, warnings);
    }

    private ParsedSop recoverFromPartialLlmJson(String jsonish, String rawText, String fileName,
                                                String providerName, String requestId) {
        LooseJsonField titleField = extractLooseJsonStringField(jsonish, "title");
        LooseJsonField descField  = extractLooseJsonStringField(jsonish, "description");
        LooseJsonField stepsField = extractLooseJsonStringField(jsonish, "resolutionSteps");
        LooseJsonField scriptField = extractLooseJsonStringField(jsonish, "mcpToolScript");
        LooseJsonField severityField = extractLooseJsonStringField(jsonish, "severity");
        LooseJsonField serviceField = extractLooseJsonStringField(jsonish, "affectedService");
        LooseJsonField teamField = extractLooseJsonStringField(jsonish, "ownerTeam");
        LooseJsonField actionsField = extractLooseJsonStringField(jsonish, "mcpActions");

        String title = titleField.value();
        String description = descField.value();
        String steps = stepsField.value();
        String mcpToolScript = scriptField.value();

        if ((title == null || title.isBlank())
                && (description == null || description.isBlank())
                && (steps == null || steps.isBlank())
                && (mcpToolScript == null || mcpToolScript.isBlank())) {
            return null;
        }

        StringBuilder enrichedDesc = new StringBuilder();
        if (description != null) enrichedDesc.append(description);
        if (severityField.value() != null) enrichedDesc.append("\n\nSeverity: ").append(severityField.value());
        if (serviceField.value() != null) enrichedDesc.append("\nAffected Service: ").append(serviceField.value());
        if (teamField.value() != null) enrichedDesc.append("\nOwner Team: ").append(teamField.value());

        String enrichedSteps = steps;
        if (actionsField.value() != null && !actionsField.value().isBlank()) {
            enrichedSteps = (steps != null ? steps : "") + "\n\n--- MCP Automation Actions ---\n" + actionsField.value();
        }

        if (title == null || title.isBlank()) {
            title = stripExtension(fileName);
        }
        if (mcpToolScript == null || mcpToolScript.isBlank()) {
            mcpToolScript = generateMcpScript(title, steps);
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("Extracted using LLM (" + providerName + ") with partial JSON recovery");
        warnings.add("LLM response was truncated/invalid JSON; verify extracted fields");
        if (stepsField.truncated()) {
            warnings.add("Resolution steps may be incomplete due to model output truncation");
        }
        if (scriptField.truncated()) {
            warnings.add("Tool script was truncated; generated a local script from extracted steps");
        }

        log.info("[SopParser][{}] PARSE-LLM PARTIAL-RECOVERY SUCCESS provider={} title='{}' stepsTruncated={}",
                requestId, providerName, title, stepsField.truncated());

        return new ParsedSop(title, enrichedDesc.toString().trim(), enrichedSteps,
                mcpToolScript, rawText, fileName, warnings);
    }

    private LooseJsonField extractLooseJsonStringField(String payload, String field) {
        if (payload == null || payload.isBlank()) return new LooseJsonField(null, false);

        String key = "\"" + field + "\"";
        int keyPos = payload.indexOf(key);
        if (keyPos < 0) return new LooseJsonField(null, false);

        int colonPos = payload.indexOf(':', keyPos + key.length());
        if (colonPos < 0) return new LooseJsonField(null, true);

        int i = colonPos + 1;
        while (i < payload.length() && Character.isWhitespace(payload.charAt(i))) i++;
        if (i >= payload.length()) return new LooseJsonField(null, true);

        if (payload.startsWith("null", i)) return new LooseJsonField(null, false);
        if (payload.charAt(i) != '"') return new LooseJsonField(null, false);

        int valueStart = i + 1;
        boolean escaped = false;
        for (int p = valueStart; p < payload.length(); p++) {
            char c = payload.charAt(p);
            if (c == '"' && !escaped) {
                String rawEscaped = payload.substring(valueStart, p);
                return new LooseJsonField(decodeJsonString(rawEscaped), false);
            }
            escaped = (c == '\\') && !escaped;
            if (c != '\\') escaped = false;
        }

        String rawEscaped = payload.substring(valueStart);
        return new LooseJsonField(decodeJsonString(rawEscaped), true);
    }

    private String decodeJsonString(String escapedValue) {
        if (escapedValue == null) return null;
        try {
            return objectMapper.readValue("\"" + escapedValue + "\"", String.class).trim();
        } catch (Exception ignored) {
            return escapedValue
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim();
        }
    }

    private record LooseJsonField(String value, boolean truncated) {}

    private String getJsonText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        if (n.isArray() || n.isObject()) return n.toString();
        String val = n.asText("").trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) ? null : val;
    }

    // ── File extraction ──────────────────────────────────────────────────────

    private String extractPdf(InputStream is) throws Exception {
        try (PDDocument doc = PDDocument.load(is)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String extractDocx(InputStream is) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append("\n");
            return sb.toString();
        }
    }

    private String extractExcel(InputStream is, boolean legacy) throws Exception {
        Workbook workbook = legacy ? WorkbookFactory.create(is) : new XSSFWorkbook(is);
        StringBuilder sb = new StringBuilder();
        try (workbook) {
            for (Sheet sheet : workbook) {
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String val = switch (cell.getCellType()) {
                            case STRING  -> cell.getStringCellValue();
                            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                            default      -> "";
                        };
                        if (!val.isBlank()) sb.append(val).append("\t");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ── Tier 3: Regex fallback — no category ─────────────────────────────────

    private ParsedSop extractFieldsRegex(String text, String filename) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Extracted using regex heuristics (LLM output unavailable or invalid)");
        String[] lines = text.split("\\r?\\n");
        String title       = extractField(text, "title");
        String description = extractField(text, "description");
        String steps       = extractSteps(text);
        if (title == null) {
            title = Arrays.stream(lines).map(String::trim)
                    .filter(l -> !l.isBlank() && l.length() > 3)
                    .map(l -> l.replaceFirst("^#+\\s*", "").replaceFirst("^SOP:\\s*", "").trim())
                    .findFirst().orElse(stripExtension(filename));
            warnings.add("Title inferred from first line");
        }
        if (description == null) {
            description = extractFirstParagraph(text);
            if (description != null) warnings.add("Description inferred from first paragraph");
        }
        if (steps == null) warnings.add("No resolution steps found - fill in manually");
        String mcpScript = generateMcpScript(title, steps);
        return new ParsedSop(title, description, steps, mcpScript, text, filename, warnings);
    }

    private String extractField(String text, String fieldName) {
        Pattern p1 = Pattern.compile("(?i)^\\s*\\**" + fieldName + "\\**\\s*[:\\-]\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = p1.matcher(text);
        if (m.find()) return m.group(1).trim().replaceAll("^\\*+|\\*+$", "");
        Pattern p2 = Pattern.compile("(?i)#+\\s*" + fieldName + "\\s*\\n+([^#\\n][^\\n]*)", Pattern.MULTILINE);
        m = p2.matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private String extractSteps(String text) {
        Pattern sp = Pattern.compile(
                "(?i)(resolution steps?|remediation steps?|remediation procedures?" +
                "|procedures?|how to fix|steps?|actions?|quick diagnostic)\\s*[:\\-]?\\s*\\n" +
                "([\\s\\S]*?)(?=\\n#{1,3}\\s|\\Z)", Pattern.MULTILINE);
        Matcher m = sp.matcher(text);
        if (m.find()) { String s = m.group(2).trim(); if (!s.isBlank()) return s; }
        Pattern np = Pattern.compile(
                "(?m)^\\s*(?:\\d+[.)\\s]|[*\\-]\\s).+(?:\\n(?!\\s*(?:\\d+[.)\\s]|[*\\-]\\s)).+)*", Pattern.MULTILINE);
        m = np.matcher(text);
        StringBuilder steps = new StringBuilder();
        int count = 0;
        while (m.find() && count < 20) { steps.append(m.group().trim()).append("\n"); count++; }
        return steps.length() > 20 ? steps.toString().trim() : null;
    }

    private String extractFirstParagraph(String text) {
        for (String p : text.split("\\n{2,}")) {
            String t = p.trim();
            if (t.length() > 30 && !t.startsWith("#") && !t.startsWith(">")
                    && !t.startsWith("|") && !t.startsWith("---")) return t;
        }
        return null;
    }

    private String stripExtension(String filename) {
        if (filename == null) return "Untitled SOP";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String generateMcpScript(String title, String steps) {
        if (title == null) title = "Untitled SOP";

        // Prefer guarded AI script generation (real executable commands), fallback to heuristic template.
        if (scriptGeneratorService != null) {
            try {
                String stepDescription = buildScriptGenDescription(title, steps);
                if (!stepDescription.isBlank()) {
                    String os = inferScriptOs(title, steps);
                    String category = inferScriptCategory(title, steps);
                    SopScriptRequest req = SopScriptRequest.builder()
                            .sopStepDescription(stepDescription)
                            .sopCategory(category)
                            .sopTitle(title)
                            .sopId("sop-parser-" + UUID.randomUUID().toString().substring(0, 8))
                            .targetHost("TARGET_HOST")
                            .os(os)
                            .additionalContext("Generated from uploaded SOP content")
                            .build();
                    String generated = scriptGeneratorService.generateFromSopStep(req);
                    if (generated != null && !generated.isBlank()) {
                        return generated;
                    }
                }
            } catch (Exception e) {
                log.warn("[SopParser] ScriptGeneratorService fallback triggered: {}", e.getMessage());
            }
        }

        String safeName = title.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        List<String> commands = extractLikelyCommands(steps);
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n# MCP Tool: ").append(title)
              .append("\n# Auto-generated remediation script\n# Tool ID: ").append(safeName)
              .append("\n\nset -euo pipefail\n\necho \"[MCP] Starting: ").append(title).append("\"\n\n");
        if (!commands.isEmpty()) {
            int idx = 1;
            for (String cmd : commands) {
                script.append("# Step ").append(idx++).append("\n");
                script.append(cmd).append("\n\n");
            }
        } else if (steps != null && !steps.isBlank()) {
            for (String line : steps.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) script.append("# ").append(trimmed).append("\n");
            }
        } else {
            script.append("# TODO: Add remediation steps\n");
        }
        script.append("\necho \"[MCP] Completed: ").append(title).append("\"\n");
        return script.toString();
    }

    private String buildScriptGenDescription(String title, String steps) {
        String base = (steps != null && !steps.isBlank()) ? steps : title;
        if (base == null) return "";
        String cleaned = base.trim();
        int max = 6000;
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private String inferScriptOs(String title, String steps) {
        String text = ((title != null ? title : "") + "\n" + (steps != null ? steps : "")).toLowerCase(Locale.ROOT);
        if (text.contains("powershell")
                || text.contains("iisreset")
                || text.contains("get-service")
                || text.contains("restart-service")
                || text.contains("stop-service")
                || text.contains("\\program files\\")
                || text.contains("c:\\")
                || text.contains("sc.exe")
                || text.contains("schtasks")) {
            return "windows";
        }
        return "linux";
    }

    private String inferScriptCategory(String title, String steps) {
        String text = ((title != null ? title : "") + "\n" + (steps != null ? steps : "")).toLowerCase(Locale.ROOT);
        if (text.contains("postgres") || text.contains("mysql") || text.contains("database") || text.contains("sql")) {
            return "DATABASE";
        }
        if (text.contains("deploy") || text.contains("kubectl") || text.contains("helm") || text.contains("rollout")) {
            return "DEPLOYMENT";
        }
        if (text.contains("cpu") || text.contains("memory") || text.contains("latency") || text.contains("performance")) {
            return "PERFORMANCE";
        }
        if (text.contains("dns") || text.contains("firewall") || text.contains("network") || text.contains("route")) {
            return "INFRASTRUCTURE";
        }
        return "APPLICATION";
    }

    private List<String> extractLikelyCommands(String steps) {
        if (steps == null || steps.isBlank()) return List.of();
        LinkedHashSet<String> cmds = new LinkedHashSet<>();
        boolean inFence = false;

        for (String raw : steps.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (line.isBlank()) continue;

            if (inFence) {
                String c = stripPromptPrefix(line);
                if (looksLikeCommand(c)) cmds.add(c);
                continue;
            }

            Matcher codeMatcher = Pattern.compile("`([^`]+)`").matcher(line);
            while (codeMatcher.find()) {
                String c = stripPromptPrefix(codeMatcher.group(1).trim());
                if (looksLikeCommand(c)) cmds.add(c);
            }

            String noBullet = line
                    .replaceFirst("^\\d+[.)]\\s*", "")
                    .replaceFirst("^[\\-*]\\s*", "");

            int colon = noBullet.indexOf(':');
            if (colon >= 0 && colon < noBullet.length() - 1) {
                String afterColon = stripPromptPrefix(noBullet.substring(colon + 1).trim());
                if (looksLikeCommand(afterColon)) cmds.add(afterColon);
            }

            String candidate = stripPromptPrefix(noBullet);
            if (looksLikeCommand(candidate)) cmds.add(candidate);
        }

        return new ArrayList<>(cmds);
    }

    private String stripPromptPrefix(String cmd) {
        if (cmd == null) return "";
        return cmd.replaceFirst("^[#$>]\\s*", "").trim();
    }

    private boolean looksLikeCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isBlank()) return false;
        if (t.startsWith("/") || t.startsWith("./") || t.startsWith("C:\\")) return true;
        String l = t.toLowerCase(Locale.ROOT);
        return l.matches("^(sudo\\s+)?(systemctl|service|curl|wget|ss|netstat|lsof|journalctl|tail|cat|grep|awk|sed|find|ps|kill|pkill|docker|kubectl|helm|nginx|apachectl|httpd|mysql|psql|redis-cli|ping|telnet|nc|traceroute|ip|ifconfig|nslookup|dig|chmod|chown|cp|mv|rm|mkdir|touch|echo|hostname|df|du|free|top|vmstat|iostat|powershell|pwsh|get-service|stop-service|start-service|restart-service|iisreset|sc\\.?exe|schtasks|net\\s+start|net\\s+stop)\\b.*");
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    private String buildCacheKey(String rawText, String fileName) {
        String filePart = fileName != null ? fileName : "unknown";
        String providerPart = llmProvider != null ? llmProvider : "unknown";
        String modelPart = directModel != null ? directModel : "default";
        return providerPart + "|" + modelPart + "|" + filePart + "|" + sha256(rawText != null ? rawText : "");
    }

    private ParsedSop getCached(String cacheKey, String requestId) {
        if (!cacheEnabled) { log.info("[SopParser][{}] CACHE disabled", requestId); return null; }
        evictExpiredEntries(requestId);
        CacheEntry entry = parseCache.get(cacheKey);
        if (entry == null) { log.info("[SopParser][{}] CACHE MISS key={}", requestId, shortKey(cacheKey)); return null; }
        long ttlMs = cacheTtlMinutes * 60_000;
        long ageMs = System.currentTimeMillis() - entry.createdAtMillis();
        if (ageMs > ttlMs) { parseCache.remove(cacheKey); log.info("[SopParser][{}] CACHE EXPIRED key={}", requestId, shortKey(cacheKey)); return null; }
        ParsedSop p = entry.parsedSop();
        List<String> w = new ArrayList<>(p.warnings() != null ? p.warnings() : List.of());
        w.add("Served from SOP parser cache");
        log.info("[SopParser][{}] CACHE HIT key={} ageMs={} cacheSize={}", requestId, shortKey(cacheKey), ageMs, parseCache.size());
        return new ParsedSop(p.title(), p.description(), p.resolutionSteps(),
                p.mcpToolScript(), p.rawText(), p.sourceFileName(), w);
    }

    private void putCache(String cacheKey, ParsedSop parsedSop, String requestId) {
        if (!cacheEnabled || parsedSop == null) return;
        boolean regexFallback = parsedSop.warnings() != null
                && parsedSop.warnings().stream()
                .map(String::toLowerCase)
                .anyMatch(w -> w.contains("regex heuristics"));
        if (regexFallback) {
            log.info("[SopParser][{}] CACHE SKIP for regex fallback result", requestId);
            return;
        }
        evictExpiredEntries(requestId);
        if (parseCache.size() >= cacheMaxEntries) {
            parseCache.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().createdAtMillis()))
                    .ifPresent(e -> { parseCache.remove(e.getKey()); log.info("[SopParser][{}] CACHE EVICT oldest", requestId); });
        }
        parseCache.put(cacheKey, new CacheEntry(parsedSop, System.currentTimeMillis()));
        log.info("[SopParser][{}] CACHE STORE key={} cacheSize={}", requestId, shortKey(cacheKey), parseCache.size());
    }

    private void evictExpiredEntries(String requestId) {
        if (!cacheEnabled || parseCache.isEmpty()) return;
        long ttlMs = cacheTtlMinutes * 60_000;
        long now = System.currentTimeMillis();
        int before = parseCache.size();
        parseCache.entrySet().removeIf(e -> (now - e.getValue().createdAtMillis()) > ttlMs);
        int evicted = before - parseCache.size();
        if (evicted > 0) log.info("[SopParser][{}] CACHE CLEANUP evicted={} remaining={}", requestId, evicted, parseCache.size());
    }

    private String shortKey(String k) { return k == null || k.length() <= 18 ? k : k.substring(k.length() - 18); }

    private String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(Objects.hashCode(text)); }
    }
}
