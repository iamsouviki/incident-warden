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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * SopDocumentParser — extracts SOP fields from uploaded documents using a
 * <b>provider-agnostic 3-tier extraction strategy</b>:
 *
 * <ol>
 *   <li><b>Spring AI ChatClient</b> — works with ANY configured provider
 *       (Ollama, OpenAI, Anthropic, Gemini). The {@code ChatClient} bean is
 *       auto-configured by {@link com.company.mcp.config.LlmProviderConfig}
 *       based on whichever starter is active.</li>
 *   <li><b>Direct HTTP fallback</b> — for {@code custom} providers or when
 *       ChatClient is absent, calls the OpenAI-compatible endpoint configured
 *       in {@code mcp.script-gen.api-url} directly.</li>
 *   <li><b>Regex heuristics</b> — keyword-based extraction when no LLM is
 *       available at all (still extracts title, category, steps, etc.).</li>
 * </ol>
 *
 * Supports: PDF (.pdf), Word (.docx), Excel (.xlsx, .xls), plain text (.txt, .md)
 *
 * <b>No files are stored on the server</b> — text is extracted in-memory,
 * sent to LLM, and the structured result is returned for DB persistence.
 */
@Slf4j
@Service
public class SopDocumentParser {

    /** Spring AI ChatClient — auto-wired when ANY provider starter is active. */
    @Autowired(required = false)
    private ChatClient chatClient;

    /** Which LLM provider is configured: ollama, openai, anthropic, gemini, custom. */
    @Value("${mcp.llm.provider:ollama}")
    private String llmProvider;

    /** Direct HTTP endpoint for custom/fallback LLM calls. */
    @Value("${mcp.script-gen.api-url:}")
    private String directApiUrl;

    /** API key for direct HTTP calls. */
    @Value("${mcp.script-gen.api-key:}")
    private String directApiKey;

    /** Model name for direct HTTP calls. */
    @Value("${mcp.script-gen.model:}")
    private String directModel;

    /** HTTP timeout for direct LLM calls. */
    @Value("${mcp.script-gen.api-timeout-ms:30000}")
    private int apiTimeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ─────────────────────────────────────────────────────────────────────────
    // Result DTO
    // ─────────────────────────────────────────────────────────────────────────

    public record ParsedSop(
            String title,
            String category,
            String description,
            String resolutionSteps,
            String rawText,
            String sourceFileName,
            List<String> warnings
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // LLM system prompt — works with ALL providers (OpenAI, Ollama, Anthropic,
    // Gemini, custom). Designed to be robust: strict JSON, no fences, short.
    // ─────────────────────────────────────────────────────────────────────────

    private static final String SOP_EXTRACTION_SYSTEM = """
            You are a structured-data extraction engine. You parse IT SOP documents
            and return ONLY a single JSON object. Never add explanation or markdown.

            Extract these fields:

            {
              "title": "short SOP title (max 120 chars)",
              "category": "APPLICATION|DATABASE|INFRASTRUCTURE|NETWORK|SECURITY|DEPLOYMENT|MEMORY|STORAGE|MONITORING|GENERAL",
              "description": "2-3 sentence summary of the problem and when this SOP applies",
              "resolutionSteps": "complete numbered remediation steps with all commands preserved exactly",
              "severity": "SEV-1|SEV-2|SEV-3|SEV-4",
              "affectedService": "primary service name (e.g. Tomcat, Nginx, PostgreSQL)",
              "ownerTeam": "responsible team (e.g. Platform SRE, DevOps, DBA)",
              "mcpActions": ["CHECK_URL:...", "RESTART_SERVICE:..."]
            }

            RULES:
            - Return ONLY the JSON object. No text before or after.
            - Use null for any field you cannot determine.
            - category MUST be one of the allowed values above.
            - resolutionSteps: preserve ALL commands and code blocks exactly.
            - mcpActions: infer automation actions from the resolution steps.
            """;

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse raw text/markdown content directly — no file upload needed.
     */
    public ParsedSop parseRawText(String content, String fileName) {
        if (content == null || content.isBlank()) {
            return new ParsedSop("Untitled SOP", "GENERAL", null, null, "",
                    fileName, List.of("Content was empty"));
        }
        log.info("[SopParser] Parsing raw text content ({} chars), hint filename='{}'",
                content.length(), fileName);
        return extractWithLlmFallback(content, fileName);
    }

    /**
     * Parse from a {@link MultipartFile} (PDF, DOCX, XLSX, TXT, MD).
     * Extracts text in-memory — no file is stored on disk.
     */
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

        log.info("[SopParser] Extracted {} chars from file '{}' (in-memory, no file stored)",
                rawText.length(), fileName);
        return extractWithLlmFallback(rawText, file.getOriginalFilename());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3-TIER EXTRACTION: ChatClient → Direct HTTP → Regex
    // ═════════════════════════════════════════════════════════════════════════

    private ParsedSop extractWithLlmFallback(String rawText, String fileName) {
        // Truncate very long documents to stay within token limits
        String textForLlm = rawText.length() > 8000
                ? rawText.substring(0, 8000) + "\n\n[... content truncated for LLM ...]"
                : rawText;

        // ── Tier 1: Spring AI ChatClient (any provider) ──────────────────────
        if (chatClient != null) {
            try {
                log.info("[SopParser] Tier-1: Using Spring AI ChatClient (provider={})", llmProvider);

                String llmResponse = chatClient.prompt()
                        .system(SOP_EXTRACTION_SYSTEM)
                        .user("Parse this SOP document and extract structured fields:\n\n" + textForLlm)
                        .call()
                        .content();

                if (llmResponse != null && !llmResponse.isBlank()) {
                    ParsedSop result = parseLlmResponse(llmResponse, rawText, fileName, llmProvider);
                    if (result != null) {
                        log.info("[SopParser] Tier-1 succeeded ({}): title='{}' category='{}'",
                                llmProvider, result.title(), result.category());
                        return result;
                    }
                }
                log.warn("[SopParser] Tier-1 returned unparseable response, trying Tier-2");
            } catch (Exception e) {
                log.warn("[SopParser] Tier-1 ChatClient failed ({}): {}", llmProvider, e.getMessage());
            }
        }

        // ── Tier 2: Direct HTTP to OpenAI-compatible endpoint ────────────────
        if (directApiUrl != null && !directApiUrl.isBlank()
                && directApiKey != null && !directApiKey.isBlank()) {
            try {
                log.info("[SopParser] Tier-2: Direct HTTP to {}", directApiUrl);

                String llmResponse = callDirectHttp(textForLlm);
                if (llmResponse != null && !llmResponse.isBlank()) {
                    String provider = "direct-http(" + directModel + ")";
                    ParsedSop result = parseLlmResponse(llmResponse, rawText, fileName, provider);
                    if (result != null) {
                        log.info("[SopParser] Tier-2 succeeded: title='{}' category='{}'",
                                result.title(), result.category());
                        return result;
                    }
                }
                log.warn("[SopParser] Tier-2 returned unparseable response, falling back to Tier-3");
            } catch (Exception e) {
                log.warn("[SopParser] Tier-2 Direct HTTP failed: {}", e.getMessage());
            }
        }

        // ── Tier 3: Regex heuristics (always available) ──────────────────────
        log.info("[SopParser] Tier-3: Using regex heuristic extraction");
        return extractFieldsRegex(rawText, fileName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Direct HTTP call to OpenAI-compatible endpoint (Tier 2)
    // Works with: Ollama /v1, OpenAI, Azure OpenAI, vLLM, LM Studio, etc.
    // ─────────────────────────────────────────────────────────────────────────

    private String callDirectHttp(String textForLlm) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", directModel != null ? directModel : "default",
                "temperature", 0.0,
                "messages", List.of(
                        Map.of("role", "system", "content", SOP_EXTRACTION_SYSTEM),
                        Map.of("role", "user", "content",
                                "Parse this SOP document and extract structured fields:\n\n" + textForLlm)
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(directApiUrl))
                .timeout(Duration.ofMillis(apiTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + directApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("[SopParser] Direct HTTP returned status {}", response.statusCode());
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM JSON response parser — handles quirks from all providers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse the LLM JSON response into a ParsedSop.
     * Handles common LLM output quirks:
     * - Markdown fences (```json ... ```)
     * - Leading/trailing text around JSON
     * - Partial JSON (tries to extract what it can)
     */
    private ParsedSop parseLlmResponse(String llmResponse, String rawText,
                                        String fileName, String providerName) {
        try {
            // Strip markdown code fences (common in all LLMs)
            String cleaned = llmResponse
                    .replaceAll("(?im)^\\s*```(?:json)?\\s*$", "")
                    .trim();

            // Find JSON object boundaries (handles text before/after JSON)
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("[SopParser] No JSON object found in LLM response (provider={})", providerName);
                return null;
            }
            cleaned = cleaned.substring(start, end + 1);

            // Fix common JSON issues from smaller models
            cleaned = cleaned
                    .replaceAll(",\\s*}", "}")           // trailing comma
                    .replaceAll(",\\s*]", "]");           // trailing comma in array

            JsonNode json = objectMapper.readTree(cleaned);

            String title       = getJsonText(json, "title");
            String category    = getJsonText(json, "category");
            String description = getJsonText(json, "description");
            String steps       = getJsonText(json, "resolutionSteps");
            String severity    = getJsonText(json, "severity");
            String service     = getJsonText(json, "affectedService");
            String ownerTeam   = getJsonText(json, "ownerTeam");
            String mcpActions  = getJsonText(json, "mcpActions");

            // Build enriched description with extra metadata
            StringBuilder enrichedDesc = new StringBuilder();
            if (description != null) enrichedDesc.append(description);
            if (severity != null)
                enrichedDesc.append("\n\nSeverity: ").append(severity);
            if (service != null)
                enrichedDesc.append("\nAffected Service: ").append(service);
            if (ownerTeam != null)
                enrichedDesc.append("\nOwner Team: ").append(ownerTeam);

            // Append MCP actions to resolution steps
            String enrichedSteps = steps;
            if (mcpActions != null && !mcpActions.isBlank() && !"null".equals(mcpActions)) {
                enrichedSteps = (steps != null ? steps : "")
                        + "\n\n--- MCP Automation Actions ---\n" + mcpActions;
            }

            // Validate & normalise category
            if (category != null) {
                category = category.toUpperCase().replaceAll("[^A-Z_]", "");
                if (!VALID_CATEGORIES.contains(category)) {
                    category = inferCategory(rawText);
                }
            }

            List<String> warnings = new ArrayList<>();
            warnings.add("Extracted using LLM (" + providerName + ")");
            if (title == null) {
                title = "Untitled SOP";
                warnings.add("LLM could not determine title");
            }
            if (category == null) {
                category = inferCategory(rawText);
                warnings.add("Category inferred from keywords: " + category);
            }

            return new ParsedSop(title, category, enrichedDesc.toString().trim(),
                    enrichedSteps, rawText, fileName, warnings);

        } catch (Exception e) {
            log.warn("[SopParser] Failed to parse LLM JSON (provider={}): {}",
                    providerName, e.getMessage());
            return null;
        }
    }

    /** Extract a string from a JSON node — handles arrays/objects as toString. */
    private String getJsonText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        if (n.isArray() || n.isObject()) return n.toString();
        String val = n.asText("").trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) ? null : val;
    }

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "APPLICATION", "DATABASE", "INFRASTRUCTURE", "NETWORK", "SECURITY",
            "DEPLOYMENT", "MEMORY", "STORAGE", "MONITORING", "GENERAL"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Text extraction from files (in-memory, no disk storage)
    // ─────────────────────────────────────────────────────────────────────────

    private String extractPdf(InputStream is) throws Exception {
        try (PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String extractDocx(InputStream is) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    private String extractExcel(InputStream is, boolean legacy) throws Exception {
        Workbook workbook = legacy
                ? WorkbookFactory.create(is)
                : new XSSFWorkbook(is);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 3: Regex fallback extraction (always available, no LLM needed)
    // ─────────────────────────────────────────────────────────────────────────

    private ParsedSop extractFieldsRegex(String text, String filename) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Extracted using regex heuristics (no LLM available)");
        String[] lines = text.split("\\r?\\n");

        String title       = extractField(text, "title");
        String category    = extractField(text, "category");
        String description = extractField(text, "description");
        String steps       = extractSteps(text);

        if (title == null) {
            title = Arrays.stream(lines)
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && l.length() > 3)
                    .map(l -> l.replaceFirst("^#+\\s*", "")
                               .replaceFirst("^SOP:\\s*", "").trim())
                    .findFirst()
                    .orElse(stripExtension(filename));
            warnings.add("Title inferred from first line");
        }

        if (category == null) {
            category = inferCategory(text);
            warnings.add("Category inferred from keywords: " + category);
        }

        if (description == null) {
            description = extractFirstParagraph(text);
            if (description != null) warnings.add("Description inferred from first paragraph");
        }

        if (steps == null) warnings.add("No resolution steps found — fill in manually");

        return new ParsedSop(title, category, description, steps, text, filename, warnings);
    }

    private String extractField(String text, String fieldName) {
        Pattern inlinePattern = Pattern.compile(
                "(?i)^\\s*\\**" + fieldName + "\\**\\s*[:\\-]\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = inlinePattern.matcher(text);
        if (m.find()) return m.group(1).trim().replaceAll("^\\*+|\\*+$", "");

        Pattern headingPattern = Pattern.compile(
                "(?i)#+\\s*" + fieldName + "\\s*\\n+([^#\\n][^\\n]*)", Pattern.MULTILINE);
        m = headingPattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        return null;
    }

    private String extractSteps(String text) {
        Pattern sectionPattern = Pattern.compile(
                "(?i)(resolution steps?|remediation steps?|remediation procedures?" +
                "|procedures?|how to fix|steps?|actions?|quick diagnostic)\\s*[:\\-]?\\s*\\n" +
                "([\\s\\S]*?)(?=\\n#{1,3}\\s|\\Z)",
                Pattern.MULTILINE);
        Matcher m = sectionPattern.matcher(text);
        if (m.find()) {
            String steps = m.group(2).trim();
            if (!steps.isBlank()) return steps;
        }

        Pattern numberedPattern = Pattern.compile(
                "(?m)^\\s*(?:\\d+[.)\\s]|[*\\-]\\s).+(?:\\n(?!\\s*(?:\\d+[.)\\s]|[*\\-]\\s)).+)*",
                Pattern.MULTILINE);
        m = numberedPattern.matcher(text);
        StringBuilder steps = new StringBuilder();
        int count = 0;
        while (m.find() && count < 20) {
            steps.append(m.group().trim()).append("\n");
            count++;
        }
        return steps.length() > 20 ? steps.toString().trim() : null;
    }

    private String inferCategory(String text) {
        String lower = text.toLowerCase();
        if (containsAny(lower, "tomcat", "nginx", "apache", "api", "http", "url",
                "503", "502", "application server")) return "APPLICATION";
        if (containsAny(lower, "database", "postgresql", "mysql", "query", "sql",
                "vacuum")) return "DATABASE";
        if (containsAny(lower, "kubernetes", "k8s", "pod", "replica",
                "kubectl")) return "INFRASTRUCTURE";
        if (containsAny(lower, "network", "firewall", "dns", "latency",
                "packet")) return "NETWORK";
        if (containsAny(lower, "memory", "heap", "oom", "out of memory",
                "jvm")) return "MEMORY";
        if (containsAny(lower, "disk", "storage", "volume", "filesystem",
                "inode")) return "STORAGE";
        if (containsAny(lower, "security", "cert", "tls", "ssl", "auth",
                "permission")) return "SECURITY";
        if (containsAny(lower, "deploy", "release", "rollback", "helm",
                "cicd")) return "DEPLOYMENT";
        if (containsAny(lower, "redis", "cache", "flush",
                "memcached")) return "DATABASE";
        if (containsAny(lower, "monitor", "prometheus", "grafana",
                "alert")) return "MONITORING";
        return "GENERAL";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    private String extractFirstParagraph(String text) {
        String[] paras = text.split("\\n{2,}");
        for (String p : paras) {
            String trimmed = p.trim();
            if (trimmed.length() > 30 && !trimmed.startsWith("#")
                    && !trimmed.startsWith(">") && !trimmed.startsWith("|")
                    && !trimmed.startsWith("---")) return trimmed;
        }
        return null;
    }

    private String stripExtension(String filename) {
        if (filename == null) return "Untitled SOP";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
