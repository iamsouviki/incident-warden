package com.company.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagFusionService {

    private static final Logger log = LoggerFactory.getLogger(RagFusionService.class);

    @Autowired(required = false)
    private VectorStore vectorStore;

    /**
     * Reciprocal Rank Fusion (RRF)
     * Constant k = 60 is standard in research (e.g. Cormack et al.)
     */
    private static final double RRF_K = 60.0;

    public List<String> expandQuery(ChatClient chatClient, String query) {
        try {
            String prompt = "You are an expert search engine assistant. Generate 3 alternative search queries " +
                    "written from different perspectives or using different technical terms / phrasing " +
                    "to retrieve relevant technical SOP documents. " +
                    "Output exactly 3 queries, one per line. Do not number them. Do not include any intro or explanation. " +
                    "Query: " + query;

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                return List.of(query);
            }

            List<String> queries = Arrays.stream(response.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> s.replaceAll("^\\d+\\.\\s*", "")) // strip leading numbers if LLM disobeyed
                    .limit(3)
                    .collect(Collectors.toList());

            // Always keep the original query as the first search query
            if (!queries.contains(query)) {
                queries.add(0, query);
            }

            log.info("[RAG-FUSION] Expanded original query into: {}", queries);
            return queries;
        } catch (Exception e) {
            log.warn("[RAG-FUSION] Query expansion failed: {}. Using original query.", e.getMessage());
            return List.of(query);
        }
    }

    public List<Document> retrieveFusedDocuments(ChatClient chatClient, String query, int topK, double threshold) {
        if (vectorStore == null) {
            log.info("[RAG-FUSION] Vector store is unavailable; returning no local documents.");
            return List.of();
        }
        List<String> expandedQueries = expandQuery(chatClient, query);

        // Map to keep track of accumulated RRF scores per unique document content
        Map<String, DocumentScore> docScoreMap = new HashMap<>();

        // Rank search results for each query variation
        for (String q : expandedQueries) {
            try {
                // Vector similarity search
                List<Document> retrieved = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(q)
                                .topK(topK * 2) // retrieve slightly more to allow robust fusion ranking
                                .similarityThreshold(threshold)
                                .filterExpression("doc_type == 'SOP'")
                                .build()
                );

                for (int rank = 0; rank < retrieved.size(); rank++) {
                    Document doc = retrieved.get(rank);
                    String contentKey = doc.getText();
                    double rrfScore = 1.0 / (rank + RRF_K);

                    docScoreMap.compute(contentKey, (k, existing) -> {
                        if (existing == null) {
                            return new DocumentScore(doc, rrfScore);
                        } else {
                            existing.addScore(rrfScore);
                            return existing;
                        }
                    });
                }
            } catch (Exception e) {
                log.error("[RAG-FUSION] Search failed for query variant '{}': {}", q, e.getMessage());
            }
        }

        // Sort fused results by RRF score descending
        return docScoreMap.values().stream()
                .sorted(Comparator.comparingDouble(DocumentScore::getScore).reversed())
                .limit(topK)
                .map(DocumentScore::getDocument)
                .collect(Collectors.toList());
    }

    private static class DocumentScore {
        private final Document document;
        private double score;

        public DocumentScore(Document document, double score) {
            this.document = document;
            this.score = score;
        }

        public void addScore(double increment) {
            this.score += increment;
        }

        public Document getDocument() {
            return document;
        }

        public double getScore() {
            return score;
        }
    }
}
