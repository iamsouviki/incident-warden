package com.company.mcp.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@Profile("local")
public class LocalMockConfig {

    @Bean
    public VectorStore vectorStore() {
        return new VectorStore() {
            private final List<Document> documents = new ArrayList<>();

            @Override
            public void add(List<Document> documents) {
                this.documents.addAll(documents);
            }

            @Override
            public void delete(List<String> idList) {
                documents.removeIf(doc -> idList.contains(doc.getId()));
            }

            @Override
            public void delete(Filter.Expression filterExpression) {
                // Not needed for local mock
            }

            @Override
            public List<Document> similaritySearch(String query) {
                return documents;
            }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                return documents;
            }
        };
    }
}
