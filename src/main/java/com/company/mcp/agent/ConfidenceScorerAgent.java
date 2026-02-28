package com.company.mcp.agent;

import com.company.mcp.model.ConfidenceLog;
import com.company.mcp.repository.ConfidenceLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Confidence Scorer Agent - Phase 5 Implementation.
 * Calculates confidence score for incident resolution decision.
 * 
 * Scoring formula (weighted components):
 * - Pattern Similarity: 35% (how similar to historical patterns)
 * - Historical Data: 25% (success rate of similar incidents)
 * - SOP Reliability: 20% (reliability of matched SOP)
 * - System Health: 15% (system health at time of incident)
 * - Risk Penalties: -5% per risk factor
 * 
 * Final Decision:
 * - Confidence >= mcp.confidence.auto-resolve-threshold (default 0.95): AUTO_RESOLVE
 * - Confidence >= 0.80 (80%): HITL_REQUIRED (human approval needed)
 * - Confidence < 0.80: ESCALATE_TO_HUMAN
 */
@Slf4j
@Component
public class ConfidenceScorerAgent extends BaseAgent {
    private final ConfidenceLogRepository confidenceLogRepository;

    // Weighting factors
    private static final double PATTERN_SIM_WEIGHT = 0.35;
    private static final double HISTORICAL_WEIGHT = 0.25;
    private static final double SOP_RELIABILITY_WEIGHT = 0.20;
    private static final double SYSTEM_HEALTH_WEIGHT = 0.15;
    private static final double RISK_PENALTY = 0.05;

    // Decision thresholds — AUTO_RESOLVE_THRESHOLD is configurable (default 0.95).
    // Set mcp.confidence.auto-resolve-threshold=1.0 to require 100% certainty.
    @Value("${mcp.confidence.auto-resolve-threshold:0.95}")
    private double autoResolveThreshold;
    private static final double HITL_THRESHOLD = 0.80;

    public ConfidenceScorerAgent(ConfidenceLogRepository confidenceLogRepository) {
        super("ConfidenceScorerAgent");
        this.confidenceLogRepository = confidenceLogRepository;
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "Phase 5: Calculating confidence score");
        
        try {
            // Calculate individual component scores
            double scorePatternSim = calculatePatternSimilarityScore(context);
            double scoreHistorical = calculateHistoricalScore(context);
            double scoreSopReliability = calculateSopReliabilityScore(context);
            double scoreSystemHealth = calculateSystemHealthScore(context);
            double penaltyRiskFactor = calculateRiskPenalty(context);

            // Calculate weighted final score
            double finalScore = (scorePatternSim * PATTERN_SIM_WEIGHT) +
                               (scoreHistorical * HISTORICAL_WEIGHT) +
                               (scoreSopReliability * SOP_RELIABILITY_WEIGHT) +
                               (scoreSystemHealth * SYSTEM_HEALTH_WEIGHT) -
                               penaltyRiskFactor;

            // Clamp score to [0, 1]
            finalScore = Math.max(0.0, Math.min(1.0, finalScore));

            // Create confidence log for audit trail
            ConfidenceLog log = new ConfidenceLog();
            log.setIncidentId(context.getIncident().getId());
            log.setPatternId(context.getMatchedPatternId());
            log.setSopId(context.getMatchedSopId());
            log.setScorePatternSim(scorePatternSim);
            log.setScoreHistorical(scoreHistorical);
            log.setScoreSopReliability(scoreSopReliability);
            log.setScoreSystemHealth(scoreSystemHealth);
            log.setPenaltyRiskFactor(penaltyRiskFactor);
            log.setFinalScore(finalScore);
            log.setReasoningText(buildReasoningText(scorePatternSim, scoreHistorical, 
                scoreSopReliability, scoreSystemHealth, penaltyRiskFactor, finalScore));

            // Determine decision based on score
            String decision = determineDecision(finalScore, context);
            log.setDecision(decision);

            context.setConfidenceLog(log);
            context.setFinalConfidenceScore(finalScore);
            context.setDecision(decision);

            logExecution(context, String.format(
                "Confidence Scoring: Pattern=%.2f, Historical=%.2f, SOP=%.2f, Health=%.2f, Risk=%.2f, Final=%.2f, Decision=%s",
                scorePatternSim, scoreHistorical, scoreSopReliability, scoreSystemHealth, penaltyRiskFactor, finalScore, decision));

            // Persist confidence log
            confidenceLogRepository.save(log);

            return context;

        } catch (Exception e) {
            handleException(context, e, "confidence scoring");
            return context;
        }
    }

    /**
     * Calculate pattern similarity score (0.0 - 1.0).
     */
    private double calculatePatternSimilarityScore(AgentContext context) {
        if (context.getPatternSimilarity() != null && context.getPatternSimilarity() > 0) {
            // Normalize to [0, 1]
            return Math.min(1.0, context.getPatternSimilarity());
        }
        return 0.3; // Default: moderate confidence if no pattern matched
    }

    /**
     * Calculate historical success rate score (0.0 - 1.0).
     * Based on success rates of similar incidents in tenant history.
     */
    private double calculateHistoricalScore(AgentContext context) {
        // In Phase 5, use a simple heuristic based on pattern success rate
        // In Phase 6+, would query historical incident data
        
        if (context.getMatchedPatternId() != null) {
            // Placeholder: assume 70% success rate for matched patterns
            return 0.70;
        }
        return 0.40; // Lower confidence without historical data
    }

    /**
     * Calculate SOP reliability score (0.0 - 1.0).
     * Based on reliability of matched SOP.
     */
    private double calculateSopReliabilityScore(AgentContext context) {
        if (context.getSopReliability() != null && context.getSopReliability() > 0) {
            return Math.min(1.0, context.getSopReliability());
        }
        return 0.5; // Medium confidence if SOP reliability unknown
    }

    /**
     * Calculate system health score (0.0 - 1.0).
     * In Phase 5, uses static default. Would integrate with monitoring in later phases.
     */
    private double calculateSystemHealthScore(AgentContext context) {
        // Phase 5: Placeholder
        // Phase 7+: Would integrate with Prometheus/Datadog metrics
        return 0.8; // Assume system is 80% healthy by default
    }

    /**
     * Calculate risk penalties (0.0 - 1.0).
     */
    private double calculateRiskPenalty(AgentContext context) {
        double penalty = 0.0;

        // Penalty for P1 severity without full certainty
        if ("P1".equals(context.getIncident().getSeverity()) && 
            context.getSopReliability() != null && context.getSopReliability() < 0.9) {
            penalty += RISK_PENALTY;
        }

        // Penalty for newly discovered patterns
        if (context.getPatternSimilarity() != null && context.getPatternSimilarity() < 0.5) {
            penalty += RISK_PENALTY * 0.5;
        }

        return penalty;
    }

    /**
     * Determine decision based on confidence score.
     */
    private String determineDecision(double score, AgentContext context) {
        // Check for overrides first
        if (context.getIncident().getSeverity() != null &&
            "P1".equals(context.getIncident().getSeverity()) &&
            score < autoResolveThreshold) {
            // P1 incidents require human approval unless confidence exceeds auto-resolve threshold
            return "HITL_REQUIRED";
        }

        if (score >= autoResolveThreshold) {
            return "AUTO_RESOLVE";
        } else if (score >= HITL_THRESHOLD) {
            return "HITL_REQUIRED";
        } else {
            return "ESCALATE_TO_HUMAN";
        }
    }

    /**
     * Build human-readable reasoning text.
     */
    private String buildReasoningText(double patternSim, double historical, 
                                     double sopReliability, double systemHealth,
                                     double riskPenalty, double finalScore) {
        return String.format(
            "Weighted confidence: Pattern(%.0f%%) + Historical(%.0f%%) + " +
            "SOP(%.0f%%) + Health(%.0f%%) - Risk(%.0f%%) = %.0f%%",
            patternSim * PATTERN_SIM_WEIGHT * 100,
            historical * HISTORICAL_WEIGHT * 100,
            sopReliability * SOP_RELIABILITY_WEIGHT * 100,
            systemHealth * SYSTEM_HEALTH_WEIGHT * 100,
            riskPenalty * 100,
            finalScore * 100
        );
    }

    @Override
    public boolean canExecute(AgentContext context) {
        // Run if classification exists; SOP match optional (0.0 score used if absent)
        return context.getClassifiedCategory() != null && context.getDecision() == null;
    }

    @Override
    public int getPriority() {
        return 4; // Runs after SOP ranker
    }
}
