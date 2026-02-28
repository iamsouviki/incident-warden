package com.company.mcp.agent;

import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
import com.company.mcp.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SOP Ranker Agent - Phase 4 RAG Implementation.
 * Ranks and matches Standard Operating Procedures using vector similarity.
 * 
 * RAG Strategy (Retrieval Augmented Generation):
 * 1. Generate embedding for incident
 * 2. Retrieve top-K relevant SOPs using pgvector similarity
 * 3. Rank by relevance + reliability score
 * 4. Filter by preconditions and scope
 * 5. Return best match with action plan
 */
@Slf4j
@Component
public class SopRankerAgent extends BaseAgent {
    private final SopProcedureRepository sopRepository;
    private final EmbeddingService embeddingService;

    private static final int TOP_K = 10;
    private static final double MIN_SIMILARITY = 0.5;
    private static final double MIN_RELIABILITY = 0.6;

    public SopRankerAgent(SopProcedureRepository sopRepository, EmbeddingService embeddingService) {
        super("SopRankerAgent");
        this.sopRepository = sopRepository;
        this.embeddingService = embeddingService;
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "Phase 4: RAG - Retrieving relevant SOPs");
        
        try {
            // Step 1: Generate combined embedding from incident + classification
            String queryText = buildQueryText(context);
            String queryEmbedding = embeddingService.generateEmbedding(queryText);
            logExecution(context, "Generated query embedding for RAG retrieval");

            // Step 2: Retrieve candidate SOPs using pgvector similarity search
            String tenantId = context.getTenantId();
            String category = context.getClassifiedCategory();
            
            List<SopProcedure> candidateSops = sopRepository.findSimilarSOPs(
                queryEmbedding, tenantId, TOP_K);
            
            logExecution(context, String.format("Retrieved %d candidate SOPs from vector search", 
                candidateSops.size()));

            // Step 3: Rank SOPs by relevance + reliability
            SopProcedure bestSop = null;
            double bestScore = 0.0;

            for (SopProcedure sop : candidateSops) {
                // Skip if not ACTIVE
                if (!"ACTIVE".equals(sop.getStatus())) {
                    continue;
                }

                // Calculate relevance score
                double similarity = embeddingService.cosineSimilarity(
                    queryEmbedding, 
                    sop.getEmbedding());

                // Weight by reliability (weighted average)
                double reliability = sop.getReliabilityScore() != null ? 
                    sop.getReliabilityScore() : 0.5;
                
                double weightedScore = (similarity * 0.7) + (reliability * 0.3);

                logExecution(context, String.format("SOP %s (%s): similarity=%.3f, reliability=%.3f, weighted=%.3f",
                    sop.getId(), sop.getTitle(), similarity, reliability, weightedScore));

                // Check if this is a good match
                if (weightedScore > bestScore && 
                    weightedScore >= MIN_SIMILARITY && 
                    reliability >= MIN_RELIABILITY) {
                    
                    bestScore = weightedScore;
                    bestSop = sop;
                }
            }

            // Step 4: Extract and populate action plan
            if (bestSop != null) {
                context.setMatchedSopId(bestSop.getId());
                context.setSopTitle(bestSop.getTitle());
                context.setSopReliability(bestSop.getReliabilityScore());
                
                // Parse action plan JSON — key must match what ActionExecutorAgent reads: "actions"
                if (bestSop.getActionPlanJson() != null) {
                    context.setActionPlan(java.util.Map.of(
                        "actions", bestSop.getActionPlanJson(),   // ActionExecutorAgent reads "actions"
                        "rollback", bestSop.getRollbackStepsJson() != null ? bestSop.getRollbackStepsJson() : "[]"
                    ));
                }

                logExecution(context, String.format(
                    "Selected SOP: %s (Score: %.3f, Reliability: %.3f)", 
                    bestSop.getTitle(), bestScore, bestSop.getReliabilityScore()));
            } else {
                context.setMatchedSopId(null);
                context.setSopTitle("No matching SOP found");
                context.setSopReliability(0.0);
                logWarning(context, "No suitable SOPs found for incident");
            }

            return context;

        } catch (Exception e) {
            handleException(context, e, "SOP ranking");
            return context;
        }
    }

    /**
     * Build query text combining incident attributes for better embedding.
     */
    private String buildQueryText(AgentContext context) {
        StringBuilder query = new StringBuilder();
        
        query.append(context.getIncident().getTitle()).append(" ");
        if (context.getIncident().getDescription() != null) {
            query.append(context.getIncident().getDescription()).append(" ");
        }
        
        query.append("Category: ").append(context.getClassifiedCategory()).append(" ");
        if (context.getClassifiedSubCategory() != null) {
            query.append("SubCategory: ").append(context.getClassifiedSubCategory()).append(" ");
        }
        
        if (context.getPatternDescription() != null) {
            query.append("Pattern: ").append(context.getPatternDescription()).append(" ");
        }

        return query.toString();
    }

    @Override
    public boolean canExecute(AgentContext context) {
        // Run if classification is available (pattern match optional)
        return context.getClassifiedCategory() != null;
    }

    @Override
    public int getPriority() {
        return 3; // Runs after pattern matcher
    }
}
