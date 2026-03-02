package com.company.mcp.agent;

import com.company.mcp.model.Incident;
import com.company.mcp.model.ConfidenceLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Context object passed through the agent pipeline.
 * Contains incident data and intermediate processing results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    // Immutable incident data
    private Incident incident;
    private String tenantId;
    private String traceId; // For distributed tracing

    // Classification results
    private String classifiedCategory;
    private String classifiedSubCategory;
    private Double classificationConfidence;
    private String classificationReason;

    // Pattern matching results
    private UUID matchedPatternId;
    private Double patternSimilarity;
    private String patternDescription;

    // SOP matching results
    private UUID matchedSopId;
    private String sopTitle;
    private Double sopReliability;
    private Map<String, Object> actionPlan; // JSON parsed action steps

    // Confidence scoring
    private ConfidenceLog confidenceLog;
    private Double finalConfidenceScore;

    // Decision
    private String decision; // AUTO_RESOLVE, HITL_REQUIRED, ESCALATE_TO_HUMAN
    private Boolean decidedByHuman;

    // Risk assessment
    private Double riskScore;
    private String riskFactors;
    private Boolean guardrailsTriggered;
    private List<String> guardRailViolations;

    // Action execution
    @Builder.Default
    private List<ActionExecutionStep> executedSteps = new ArrayList<>();
    private Boolean rollbackTriggered;
    private String rollbackReason;

    // ── Dual-source RAG: SOP + Resolved Incident KB ───────────────────────────
    /**
     * LLM-generated resolution suggestion produced by consulting <em>both</em>
     * the SOP library and the Resolved Incident Knowledge Base.
     * Populated by {@link SopRankerAgent} during Phase-1.
     */
    private String kbSuggestedResolution;

    /**
     * Summary metadata of the top-K resolved KB entries that matched this
     * incident. Each map contains: {@code id}, {@code title},
     * {@code resolutionSummary}, {@code rootCause}, {@code severity},
     * {@code resolvedBy}, {@code resolvedAt}.
     */
    @Builder.Default
    private List<Map<String, Object>> kbMatchedEntries = new ArrayList<>();

    /**
     * Raw {@link Document} objects retrieved from the combined SOP + KB search.
     * Available for downstream agents to inspect individual passages.
     */
    @Builder.Default
    private List<Document> combinedRagDocs = new ArrayList<>();

    // Timeline tracking
    private LocalDateTime processingStartedAt;
    private LocalDateTime processingCompletedAt;

    // Error tracking
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    public void addError(String error) {
        this.errors.add(error);
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public boolean hasErrors() {
        return !this.errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !this.warnings.isEmpty();
    }

    /**
     * Inner class for tracking action execution steps
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionExecutionStep {
        private int stepNumber;
        private String toolName;
        private Map<String, Object> parameters;
        private Map<String, Object> result;
        private String status; // SUCCESS, FAILED, ROLLED_BACK
        private LocalDateTime executedAt;
        private Long durationMs;
    }
}
