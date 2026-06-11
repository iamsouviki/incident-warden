package com.company.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.core.io.Resource;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private AiConfigService aiConfigService;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private org.springframework.web.client.RestClient.Builder restClientBuilder;

    @Autowired
    private RagFusionService ragFusionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ChatClient chatClient;
    private String cachedProvider;
    private String cachedBaseUrl;
    private String cachedApiKey;
    private String cachedModel;

    @jakarta.annotation.PostConstruct
    public void init() {
        // Fallback to auto-configured builder initially
        if (chatClientBuilder != null) {
            this.chatClient = chatClientBuilder.build();
        }
    }

    private synchronized ChatClient getOrBuildChatClient() {
        String provider = aiConfigService.getProvider();
        String baseUrl = aiConfigService.getBaseUrl();
        String apiKey = aiConfigService.getApiKey();
        String model = aiConfigService.getActiveChatModel();

        if (chatClient != null 
                && Objects.equals(provider, cachedProvider)
                && Objects.equals(baseUrl, cachedBaseUrl)
                && Objects.equals(apiKey, cachedApiKey)
                && Objects.equals(model, cachedModel)) {
            return chatClient;
        }

        log.info("[RAG] Configuring ChatClient dynamically for provider={} model={} url={}", provider, model, baseUrl);
        try {
            org.springframework.ai.chat.model.ChatModel modelInstance;
            if ("ollama".equalsIgnoreCase(provider)) {
                OllamaApi api = OllamaApi.builder()
                        .baseUrl(baseUrl)
                        .restClientBuilder(restClientBuilder)
                        .build();
                modelInstance = OllamaChatModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(OllamaOptions.builder().model(model).build())
                        .build();
            } else {
                // OpenAI, Groq, OpenRouter, Anthropic (if OpenAI-compatible endpoint), etc.
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .restClientBuilder(restClientBuilder)
                        .build();
                modelInstance = OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                        .build();
            }

            this.chatClient = ChatClient.builder(modelInstance).build();
            this.cachedProvider = provider;
            this.cachedBaseUrl = baseUrl;
            this.cachedApiKey = apiKey;
            this.cachedModel = model;
        } catch (Exception e) {
            log.error("[RAG] Failed to build dynamic ChatClient: {}. Falling back to default.", e.getMessage());
            if (this.chatClient == null && chatClientBuilder != null) {
                this.chatClient = chatClientBuilder.build();
            }
        }
        return chatClient;
    }

    @Value("${mcp.rag.top-k:5}")
    private int defaultTopK;

    @Value("${mcp.rag.similarity-threshold:0.6}")
    private double defaultSimilarityThreshold;

    @Value("${mcp.rag.enabled:true}")
    private boolean ragEnabled;

    public static final String TYPE_SOP = "SOP";

    public boolean ingest(String id, String content, String type, Map<String, Object> metadata) {
        if (!isVectorStoreAvailable()) return false;

        try {
            Map<String, Object> meta = new HashMap<>(metadata != null ? metadata : Map.of());
            meta.put("source_id", id);
            meta.put("doc_type",  type);

            Document doc = Document.builder()
                    .text(content)
                    .metadata(meta)
                    .build();
            vectorStore.add(List.of(doc));
            log.info("[RAG] Ingested document id={} type={}", id, type);
            return true;
        } catch (Exception e) {
            log.error("[RAG] Failed to ingest document id={}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean ingestSop(String title, String description) {
        String content = String.format("SOP: %s\nDescription: %s", title, description);
        String id = UUID.randomUUID().toString();
        return ingest(id, content, TYPE_SOP, Map.of("sop_title", title));
    }

    public boolean ingestFile(Resource resource, String title) {
        if (!isVectorStoreAvailable()) return false;
        try {
            log.info("[RAG] Parsing file: {}", resource.getFilename());
            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            List<Document> parsedDocs = documentReader.get();

            log.info("[RAG] Chunking parsed document...");
            TokenTextSplitter splitter = new TokenTextSplitter(800, 400, 10, 10000, true);
            List<Document> chunkedDocs = splitter.apply(parsedDocs);

            // Add metadata
            String docId = UUID.randomUUID().toString();
            for (Document doc : chunkedDocs) {
                doc.getMetadata().put("source_id", docId);
                doc.getMetadata().put("doc_type", TYPE_SOP);
                if (title != null && !title.isBlank()) {
                    doc.getMetadata().put("sop_title", title);
                }
                doc.getMetadata().put("file_name", resource.getFilename());
            }

            log.info("[RAG] Saving {} chunks to VectorStore...", chunkedDocs.size());
            vectorStore.add(chunkedDocs);
            log.info("[RAG] File ingested successfully.");
            return true;
        } catch (Exception e) {
            log.error("[RAG] Failed to ingest file {}: {}", resource.getFilename(), e.getMessage());
            return false;
        }
    }

    @Cacheable(value = "ragAnswers", key = "#sessionId + '_' + #question")
    public String askStrictSopRag(String sessionId, String question) {
        ChatClient activeClient = getOrBuildChatClient();
        if (!isVectorStoreAvailable() || activeClient == null) return "RAG unavailable. Please check Vector DB and ChatClient.";
        try {
            if (isConversationalQuery(question)) {
                return handleConversationalQuery(activeClient, question);
            }

            log.info("[RAG-HYBRID] Performing advanced hybrid RAG for: {}", question);

            // 1. Get fused semantic results (Vector Search + Query Expansion)
            List<Document> semanticDocs = ragFusionService.retrieveFusedDocuments(
                    activeClient, question, defaultTopK, defaultSimilarityThreshold);

            // 2. Get lexical results (Full-Text Search)
            List<Document> lexicalDocs = Collections.emptyList();
            try {
                lexicalDocs = jdbcTemplate.query(
                    "SELECT id, content, metadata FROM mcp_rag.vector_store " +
                    "WHERE fts_vector @@ plainto_tsquery('english', ?) LIMIT ?",
                    (rs, rowNum) -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = new HashMap<>();
                        try {
                            metadata = objectMapper.readValue(metadataJson, Map.class);
                        } catch (Exception ignored) {}
                        return new Document(id, content, metadata);
                    },
                    question,
                    defaultTopK
                );
                log.info("[RAG-HYBRID] Retrieved {} FTS documents", lexicalDocs.size());
            } catch (Exception e) {
                log.error("[RAG-HYBRID] Lexical search failed: {}", e.getMessage());
            }

            // 3. Fused Semantic + Lexical via RRF
            List<Document> hybridDocs = rrfMerge(semanticDocs, lexicalDocs, defaultTopK);
            log.info("[RAG-HYBRID] Merged hybrid context contains {} documents", hybridDocs.size());

            // 4. Build strict context prompt
            String context = hybridDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));

            String prompt = "You are a strict technical assistant. Answer the user's question solely based on the provided retrieved SOP context. If the answer is not present in the context, you must reply exactly with 'NOT_FOUND'. Do not answer anything outside these docs.\n\n" +
                    "Context:\n" + context + "\n\n" +
                    "Question: " + question;

            String activeModel = aiConfigService.getActiveChatModel();
            log.info("[RAG] Routing chat query to model: {}", activeModel);
 
            String answer = activeClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            
            if (answer == null || answer.trim().equals("NOT_FOUND")) {
                log.info("[RAG] Answer not found in context.");
                return "I'm sorry, but I couldn't find the answer to your question in the currently ingested SOP documents.";
            }
            
            log.info("[RAG] Strict answer generated ({} chars)", answer.length());
            return answer;
        } catch (Exception e) {
            log.error("[RAG] askStrictSopRag failed: {}", e.getMessage());
            return "I'm sorry, but an error occurred while searching the SOPs.";
        }
    }

    private boolean isConversationalQuery(String query) {
        if (query == null) return false;
        String clean = query.trim().toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        if (clean.length() < 2) return true;

        Set<String> staticTriggers = Set.of(
            "hi", "hello", "hey", "hola", "greetings", "yo", "sup",
            "how are you", "how goes it", "whats up", "what is up",
            "who are you", "what is your name", "whats your name",
            "what can you do", "help", "menu", "options", "start",
            "ok", "okay", "cool", "nice", "thanks", "thank you", "bye", "goodbye", "test", "testing"
        );
        return staticTriggers.contains(clean);
    }

    private String handleConversationalQuery(ChatClient activeClient, String question) {
        String clean = question.trim().toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        log.info("[RAG] Handling conversational query instantly (no LLM call): '{}'", clean);

        switch (clean) {
            case "hi":
            case "hello":
            case "hey":
            case "hola":
            case "greetings":
            case "yo":
            case "sup":
            case "start":
                return "Hello! I am your SOP assistant. How can I help you today?";
            
            case "how are you":
            case "how goes it":
            case "whats up":
            case "what is up":
                return "I'm doing great, thank you! Ready to help you search your SOP documents.";
            
            case "who are you":
            case "what is your name":
            case "whats your name":
                return "I am the MCP Incident Automation SOP Assistant, here to answer your SOP queries.";
            
            case "what can you do":
            case "help":
            case "menu":
            case "options":
                return "I can help you search and retrieve details from your ingested SOP documents. You can upload SOP files, type technical questions, or change LLM settings in the configuration tab.";
            
            case "ok":
            case "okay":
            case "cool":
            case "nice":
                return "Great! Let me know if you have any questions about the SOPs.";
            
            case "thanks":
            case "thank you":
                return "You're welcome! Happy to help.";
            
            case "bye":
            case "goodbye":
                return "Goodbye! Have a great day.";
            
            case "test":
            case "testing":
                return "Test successful! I am online and ready.";
            
            default:
                // Fallback to quick LLM call if not in static list but matches pattern
                log.info("[RAG] Conversational fallback to quick LLM call");
                String prompt = "You are a helpful technical assistant for the SOP platform. Respond to: " + question;
                return activeClient.prompt().user(prompt).call().content();
        }
    }

    private List<Document> rrfMerge(List<Document> semanticDocs, List<Document> lexicalDocs, int topK) {
        Map<String, DocumentScore> merged = new HashMap<>();
        double k = 60.0;

        for (int rank = 0; rank < semanticDocs.size(); rank++) {
            Document doc = semanticDocs.get(rank);
            double score = 1.0 / (rank + k);
            merged.put(doc.getText(), new DocumentScore(doc, score));
        }

        for (int rank = 0; rank < lexicalDocs.size(); rank++) {
            Document doc = lexicalDocs.get(rank);
            double score = 1.0 / (rank + k);
            merged.compute(doc.getText(), (key, existing) -> {
                if (existing == null) {
                    return new DocumentScore(doc, score);
                } else {
                    existing.score += score;
                    return existing;
                }
            });
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(d -> -d.score))
                .limit(topK)
                .map(d -> d.document)
                .toList();
    }

    private static class DocumentScore {
        Document document;
        double score;
        DocumentScore(Document document, double score) {
            this.document = document;
            this.score = score;
        }
    }

    public boolean isVectorStoreAvailable() {
        return ragEnabled && vectorStore != null;
    }
}
