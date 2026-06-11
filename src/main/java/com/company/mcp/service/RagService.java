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
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.cache.annotation.Cacheable;

import java.util.*;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private AiConfigService aiConfigService;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        if (chatClientBuilder != null) {
            this.chatClient = chatClientBuilder.build();
        }
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
        if (!isVectorStoreAvailable() || chatClient == null) return "RAG unavailable. Please check Vector DB and ChatClient.";
        try {
            String prompt = "You are a strict technical assistant. Answer the user's question solely based on the provided retrieved SOP context. If the answer is not present in the context, you must reply exactly with 'NOT_FOUND'. Do not answer anything outside these docs.\n\nQuestion: " + question;
            
            String activeModel = aiConfigService.getActiveChatModel();
            log.info("[RAG] Routing chat query to model: {}", activeModel);

            String answer = chatClient.prompt()
                    .options(OllamaOptions.builder().model(activeModel).build())
                    .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(SearchRequest.builder()
                                    .topK(defaultTopK)
                                    .similarityThreshold(defaultSimilarityThreshold)
                                    .filterExpression("doc_type == '" + TYPE_SOP + "'")
                                    .build())
                            .build())
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

    public boolean isVectorStoreAvailable() {
        return ragEnabled && vectorStore != null;
    }
}
