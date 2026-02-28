package com.company.mcp.agent;

import com.company.mcp.model.Incident;
import com.company.mcp.repository.ClassificationRulesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Classifier Agent - Phase 3 Implementation.
 * Categorizes incidents using regex rules and semantic classification.
 * 
 * Classification strategy:
 * 1. Try regex-based rules first (fast, deterministic)
 * 2. Fall back to semantic classification (flexibility)
 * 3. Assign confidence scores based on match quality
 */
@Slf4j
@Component
public class ClassifierAgent extends BaseAgent {
    private final ClassificationRulesRepository rulesRepository;

    public ClassifierAgent(ClassificationRulesRepository rulesRepository) {
        super("ClassifierAgent");
        this.rulesRepository = rulesRepository;
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "Phase 3: Classifying incident");
        
        try {
            Incident incident = context.getIncident();
            UUID tenantId = context.getTenantId() != null ? UUID.fromString(context.getTenantId()) : null;

            // Step 1: Try regex-based rule matching
            ClassificationResult ruleResult = matchRegexRules(incident, tenantId);
            
            if (ruleResult.matched) {
                context.setClassifiedCategory(ruleResult.category);
                context.setClassifiedSubCategory(ruleResult.subCategory);
                context.setClassificationConfidence(ruleResult.confidence);
                context.setClassificationReason("Matched regex rule #" + ruleResult.ruleId);
                
                logExecution(context, String.format("Rule-based classification: %s/%s (confidence: %.2f)",
                    ruleResult.category, ruleResult.subCategory, ruleResult.confidence));
                
                return context;
            }

            // Step 2: Fall back to semantic classification if available
            ClassificationResult semanticResult = semanticClassification(incident);
            
            context.setClassifiedCategory(semanticResult.category);
            context.setClassifiedSubCategory(semanticResult.subCategory);
            context.setClassificationConfidence(semanticResult.confidence);
            context.setClassificationReason("Semantic classification (LLM)");
            
            logExecution(context, String.format("Semantic classification: %s/%s (confidence: %.2f)",
                semanticResult.category, semanticResult.subCategory, semanticResult.confidence));
            
            return context;

        } catch (Exception e) {
            handleException(context, e, "classification");
            return context;
        }
    }

    /**
     * Match incident against regex-based classification rules.
     */
    private ClassificationResult matchRegexRules(Incident incident, UUID tenantId) {
        if (rulesRepository == null) {
            return ClassificationResult.unmatched();
        }

        var rules = rulesRepository.findActivePrioritized(tenantId);
        
        for (var rule : rules) {
            try {
                Pattern pattern = Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE);
                
                // Check title and description
                if (pattern.matcher(incident.getTitle()).find() ||
                    (incident.getDescription() != null && pattern.matcher(incident.getDescription()).find())) {
                    
                    return ClassificationResult.builder()
                        .matched(true)
                        .ruleId(rule.getId().toString())
                        .category(rule.getCategory())
                        .subCategory(rule.getSubCategory())
                        .confidence(rule.getConfidence() != null ? rule.getConfidence() : 0.95)
                        .build();
                }
            } catch (Exception e) {
                log.warn("Failed to compile/match regex rule {}: {}", rule.getId(), e.getMessage());
            }
        }
        
        return ClassificationResult.unmatched();
    }

    /**
     * Semantic classification using heuristics and LLM fallback.
     * Phase 3 uses heuristics; Phase 5+ would integrate with LLM API.
     */
    private ClassificationResult semanticClassification(Incident incident) {
        String title = incident.getTitle().toLowerCase();
        String description = incident.getDescription() != null ? 
            incident.getDescription().toLowerCase() : "";

        // Heuristic-based classification
        if (title.contains("database") || title.contains("postgres") || description.contains("sql")) {
            return ClassificationResult.builder()
                .matched(true)
                .category("DATABASE")
                .subCategory("PERFORMANCE")
                .confidence(0.75)
                .build();
        }
        
        if (title.contains("network") || title.contains("connectivity") || title.contains("timeout")) {
            return ClassificationResult.builder()
                .matched(true)
                .category("NETWORK")
                .subCategory("CONNECTIVITY")
                .confidence(0.70)
                .build();
        }
        
        if (title.contains("cpu") || title.contains("memory") || title.contains("disk")) {
            return ClassificationResult.builder()
                .matched(true)
                .category("INFRASTRUCTURE")
                .subCategory("RESOURCE")
                .confidence(0.75)
                .build();
        }
        
        if (title.contains("deployment") || title.contains("release")) {
            return ClassificationResult.builder()
                .matched(true)
                .category("DEPLOYMENT")
                .subCategory("ROLLOUT")
                .confidence(0.80)
                .build();
        }

        // Default classification
        return ClassificationResult.builder()
            .matched(true)
            .category("OTHER")
            .subCategory("GENERAL")
            .confidence(0.50)
            .build();
    }

    @Override
    public boolean canExecute(AgentContext context) {
        return context.getIncident() != null && 
               context.getIncident().getTitle() != null;
    }

    @Override
    public int getPriority() {
        return 1; // Runs after orchestrator, before pattern matching
    }

    /**
     * Inner class for classification results.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class ClassificationResult {
        private boolean matched;
        private String ruleId;
        private String category;
        private String subCategory;
        private Double confidence;

        static ClassificationResult unmatched() {
            return ClassificationResult.builder()
                .matched(false)
                .category("UNKNOWN")
                .subCategory("UNCLASSIFIED")
                .confidence(0.0)
                .build();
        }
    }
}
