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
    private com.company.mcp.repository.VectorStoreEntityRepository vectorStoreEntityRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.company.mcp.repository.IncidentRepository incidentRepository;

    @Autowired
    private com.company.mcp.repository.ExternalIncidentRepository externalIncidentRepository;

    private static final String OUT_OF_SCOPE_MESSAGE = "I can only answer questions grounded in your organization’s SOPs and incident records. Please ask about an uploaded procedure, runbook, store device, or incident.";
    private static final String NO_EVIDENCE_MESSAGE = "I couldn’t find supporting content in the current SOPs or incident records. Please upload the relevant SOP or ask a more specific operational question.";

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

    public synchronized org.springframework.ai.chat.client.ChatClient getOrBuildChatClient() {
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
        return ingestSop("tenant-1", title, description);
    }

    public boolean ingestSop(String tenantId, String title, String description) {
        String content = String.format("SOP: %s\nDescription: %s", title, description);
        String id = UUID.randomUUID().toString();
        return ingest(id, content, TYPE_SOP, Map.of("sop_title", title, "tenant_id", tenantId, "approval_status", "APPROVED"));
    }

    public boolean ingestFile(Resource resource, String title) {
        return ingestFile(resource, title, "tenant-1");
    }

    public boolean ingestFile(Resource resource, String title, String tenantId) {
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
                doc.getMetadata().put("tenant_id", tenantId);
                doc.getMetadata().put("approval_status", "APPROVED");
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

    /**
     * Returns only approved SOP chunks owned by the requested tenant. This is
     * the planner contract; conversational answers are intentionally not used
     * as proof that a remediation procedure exists.
     */
    public SopEvidence findApprovedSopEvidence(String tenantId, String query) {
        if (tenantId == null || tenantId.isBlank()) return SopEvidence.unavailable("TENANT_CONTEXT_MISSING");
        if (!isVectorStoreAvailable()) return SopEvidence.unavailable("SOP_SERVICE_UNAVAILABLE");
        if (query == null || query.isBlank()) return SopEvidence.noMatch("EMPTY_INCIDENT_CONTEXT");
        try {
            List<com.company.mcp.model.VectorStoreEntity> entities = vectorStoreEntityRepository
                    .findApprovedSopsByTenantAndFullTextSearch(tenantId, query, Math.max(1, defaultTopK));
            if (entities.isEmpty()) return SopEvidence.noMatch("NO_APPROVED_TENANT_SOP_MATCH");
            List<UUID> ids = entities.stream().map(com.company.mcp.model.VectorStoreEntity::getId)
                    .filter(Objects::nonNull).toList();
            String excerpt = entities.stream().map(com.company.mcp.model.VectorStoreEntity::getContent)
                    .filter(Objects::nonNull).collect(Collectors.joining("\n\n"));
            if (ids.isEmpty() || excerpt.isBlank()) return SopEvidence.noMatch("EMPTY_APPROVED_SOP_MATCH");
            return new SopEvidence(true, true, ids, excerpt.substring(0, Math.min(6000, excerpt.length())), 0.90, "APPROVED_TENANT_SOP_MATCH");
        } catch (Exception e) {
            log.warn("[RAG] Approved SOP evidence lookup failed: {}", e.getMessage());
            return SopEvidence.unavailable("SOP_EVIDENCE_LOOKUP_FAILED");
        }
    }

    @Cacheable(value = "ragAnswers", key = "#sessionId + '_' + #question")
    public String askStrictSopRag(String sessionId, String question) {
        if (question == null || question.isBlank()) return "Please ask a question about an SOP or incident.";

        ChatClient activeClient = getOrBuildChatClient();
        if (isConversationalQuery(question)) {
            return handleConversationalQuery(activeClient, question);
        }
        if (!isWithinSopScope(question)) return OUT_OF_SCOPE_MESSAGE;
        if (!isVectorStoreAvailable() || activeClient == null) return "The SOP knowledge service is not available in this environment. Start the configured knowledge provider or use the local Docker profile.";
        try {

            log.info("[RAG-HYBRID] Performing advanced hybrid RAG for: {}", question);

            // 1. Get fused semantic results (Vector Search + Query Expansion)
            List<Document> semanticDocs = ragFusionService.retrieveFusedDocuments(
                    activeClient, question, defaultTopK, defaultSimilarityThreshold);

            // 2. Get lexical results (Full-Text Search)
            List<Document> lexicalDocs = new ArrayList<>();
            try {
                java.util.List<com.company.mcp.model.VectorStoreEntity> entities = 
                    vectorStoreEntityRepository.findByFullTextSearch(question, defaultTopK);
                for (com.company.mcp.model.VectorStoreEntity ent : entities) {
                    Map<String, Object> metadata = new HashMap<>();
                    if (ent.getMetadata() != null) {
                        try {
                            metadata = objectMapper.readValue(ent.getMetadata(), Map.class);
                        } catch (Exception ignored) {}
                    }
                    lexicalDocs.add(new Document(
                        ent.getId() != null ? ent.getId().toString() : UUID.randomUUID().toString(),
                        ent.getContent(),
                        metadata
                    ));
                }
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

            // 5. Fetch all incidents context
            StringBuilder incidentsContext = new StringBuilder();
            try {
                List<com.company.mcp.model.Incident> manual = incidentRepository.findAll();
                List<com.company.mcp.model.ExternalIncident> external = externalIncidentRepository.findAll();
                for (com.company.mcp.model.Incident inc : manual) {
                    incidentsContext.append(String.format("- Ticket: %s, Subject: '%s', Status: %s, Assignee: %s, Assigned Team: %s, Priority: %s, Created: %s\n",
                        inc.getExternalId(), inc.getSubject(), inc.getStatus(), inc.getAssignee(), inc.getAssignedGteam(), inc.getPriority(), inc.getCreatedAt()));
                }
                for (com.company.mcp.model.ExternalIncident ext : external) {
                    incidentsContext.append(String.format("- Ticket: %s, Subject: '%s', Status: %s, Assignee: %s, Assigned Team: %s, Priority: %s, Source: %s, Created: %s\n",
                        ext.getExternalId(), ext.getSubject(), ext.getStatus(), ext.getAssignee(), ext.getAssignedGteam(), ext.getPriority(), ext.getExternalSource(), ext.getCreatedAt()));
                }
            } catch (Exception e) {
                log.error("[RAG] Failed to build incidents context: {}", e.getMessage());
            }

            if (hybridDocs.isEmpty() && incidentsContext.isEmpty()) {
                return NO_EVIDENCE_MESSAGE;
            }

            String prompt = "You are the SOP and incident operations assistant. You must stay strictly within the supplied evidence.\n\n" +
                    "SOP Context:\n" + context + "\n\n" +
                    "System Incident Data:\n" + incidentsContext.toString() + "\n\n" +
                    "User question:\n" + question + "\n\n" +
                    "Non-negotiable instructions:\n" +
                    "- Answer only when the answer is directly supported by the SOP Context or System Incident Data.\n" +
                    "- Never use general knowledge, assumptions, training data, or invented procedures.\n" +
                    "- If the evidence is insufficient or the question is outside SOP/incident operations, reply exactly with: " + NO_EVIDENCE_MESSAGE + "\n" +
                    "- Do not discuss politics, entertainment, coding unrelated to this platform, personal advice, or general trivia.\n" +
                    "- Keep answers concise and cite the relevant SOP title, step, incident ID, or field when available.";

            String activeModel = aiConfigService.getActiveChatModel();
            log.info("[RAG] Routing chat query to model: {}", activeModel);
 
            String answer = activeClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            
            if (answer == null) {
                log.info("[RAG] No answer generated.");
                return "I'm sorry, but I couldn't generate an answer to that question.";
            }
            
            log.info("[RAG] Answer generated ({} chars)", answer.length());
            return answer;
        } catch (Exception e) {
            log.error("[RAG] askStrictSopRag failed: {}", e.getMessage());
            return "I'm sorry, but an error occurred while generating the answer.";
        }
    }

    private boolean isWithinSopScope(String query) {
        String clean = query == null ? "" : query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s-]", " ");
        Set<String> scopeTerms = Set.of(
            "sop", "procedure", "runbook", "playbook", "checklist", "policy", "standard operating",
            "troubleshoot", "troubleshooting", "diagnose", "remediate", "remediation", "resolve", "fix",
            "incident", "alert", "outage", "error", "failure", "root cause", "postmortem", "ticket",
            "store", "device", "pos", "register", "kiosk", "scanner", "printer", "terminal", "pinpad",
            "payment", "network", "router", "switch", "vpn", "wifi", "inventory", "deployment", "service",
            "restart", "reset", "configure", "configuration", "install", "escalate", "maintenance", "agent"
        );
        return scopeTerms.stream().anyMatch(clean::contains);
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
                return "I’m here to help with questions grounded in your SOPs and incident records.";
            
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
                return OUT_OF_SCOPE_MESSAGE;
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

    public List<com.company.mcp.model.VectorStoreEntity> getAllSops() {
        return vectorStoreEntityRepository.findAllSops();
    }

    public List<com.company.mcp.model.VectorStoreEntity> getAllSops(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return List.of();
        try { return vectorStoreEntityRepository.findAllSopsByTenant(tenantId); }
        catch (Exception e) { log.warn("[RAG] Tenant SOP listing failed: {}", e.getMessage()); return List.of(); }
    }

    public boolean updateSop(UUID id, String title, String description) {
        if (!isVectorStoreAvailable()) return false;
        try {
            vectorStore.delete(List.of(id.toString()));
            String content = String.format("SOP: %s\nDescription: %s", title, description);
            org.springframework.ai.document.Document doc = org.springframework.ai.document.Document.builder()
                    .id(id.toString())
                    .text(content)
                    .metadata(Map.of("sop_title", title, "doc_type", TYPE_SOP))
                    .build();
            vectorStore.add(List.of(doc));
            log.info("[RAG] Updated and re-embedded SOP id={}", id);
            return true;
        } catch (Exception e) {
            log.error("[RAG] Failed to update SOP id={}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean deleteSop(UUID id) {
        if (!isVectorStoreAvailable()) return false;
        try {
            vectorStore.delete(List.of(id.toString()));
            log.info("[RAG] Deleted SOP id={}", id);
            return true;
        } catch (Exception e) {
            log.error("[RAG] Failed to delete SOP id={}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean isVectorStoreAvailable() {
        return ragEnabled && vectorStore != null;
    }
}
