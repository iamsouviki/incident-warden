package com.company.mcp.agent;

import com.company.mcp.model.IncidentPattern;
import com.company.mcp.repository.PatternRepository;
import com.company.mcp.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pattern Matcher Agent - Phase 4 Implementation.
 * Finds similar historical incident patterns using vector similarity search (pgvector).
 * 
 * Strategy:
 * 1. Generate embedding for current incident
 * 2. Search similar patterns using pgvector cosine distance
 * 3. Score matches based on reliability and success rates
 * 4. Return best match above threshold
 */
@Slf4j
@Component
public class PatternMatcherAgent extends BaseAgent {
    private final PatternRepository patternRepository;
    private final EmbeddingService embeddingService;

    private static final int TOP_K = 5;
    private static final double MIN_SIMILARITY = 0.6;

    public PatternMatcherAgent(PatternRepository patternRepository, EmbeddingService embeddingService) {
        super("PatternMatcherAgent");
        this.patternRepository = patternRepository;
        this.embeddingService = embeddingService;
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "Phase 4: Finding similar incident patterns");
        
        try {
            // Step 1: Generate embedding for incident description
            String incidentText = context.getIncident().getTitle() + " " + 
                                 (context.getIncident().getDescription() != null ? 
                                  context.getIncident().getDescription() : "");
            
            String incidentEmbedding = embeddingService.generateEmbedding(incidentText);
            logExecution(context, "Generated incident embedding");

            // Step 2: Search for similar patterns using pgvector
            String tenantId = context.getTenantId();
            String category = context.getClassifiedCategory();
            
            List<IncidentPattern> similarPatterns = patternRepository.findSimilarPatterns(
                incidentEmbedding, tenantId, category, TOP_K);
            
            logExecution(context, String.format("Found %d similar patterns", similarPatterns.size()));

            // Step 3: Score and select best match
            IncidentPattern bestMatch = null;
            double bestSimilarity = 0.0;

            for (IncidentPattern pattern : similarPatterns) {
                // Calculate similarity
                double similarity = embeddingService.cosineSimilarity(
                    incidentEmbedding, 
                    pattern.getEmbedding());
                
                // Weight by reliability
                double adjustedScore = similarity * (pattern.getReliabilityScore() != null ? 
                    pattern.getReliabilityScore() : 0.5);

                logExecution(context, String.format("Pattern %s: similarity=%.3f, adjusted=%.3f",
                    pattern.getId(), similarity, adjustedScore));

                if (adjustedScore > bestSimilarity && adjustedScore >= MIN_SIMILARITY) {
                    bestSimilarity = adjustedScore;
                    bestMatch = pattern;
                }
            }

            // Step 4: Update context with results
            if (bestMatch != null) {
                context.setMatchedPatternId(bestMatch.getId());
                context.setPatternSimilarity(bestSimilarity);
                context.setPatternDescription(bestMatch.getDescription());
                
                logExecution(context, String.format(
                    "Selected pattern %s with similarity %.3f", 
                    bestMatch.getId(), bestSimilarity));
            } else {
                context.setMatchedPatternId(null);
                context.setPatternSimilarity(0.0);
                logWarning(context, "No similar patterns found above threshold");
            }

            return context;

        } catch (Exception e) {
            handleException(context, e, "pattern matching");
            return context;
        }
    }

    @Override
    public boolean canExecute(AgentContext context) {
        // Runs in parallel with ClassifierAgent — no pre-classification required
        return context.getIncident() != null;
    }

    @Override
    public int getPriority() {
        return 2; // Phase-1 parallel with Classifier and SopRanker
    }
}
