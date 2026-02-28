package com.company.mcp.domain;

/**
 * Confidence-based decision routing for incidents.
 * - AUTO_RESOLVE: confidence >= 100% (after 9 guardrail checks)
 * - HITL_REQUIRED: confidence 80-99% (human approval needed)
 * - ESCALATE_TO_HUMAN: confidence < 80% (escalate to on-call)
 */
public enum Decision {
    AUTO_RESOLVE("Auto-resolve with no human intervention", 1.0),
    HITL_REQUIRED("Requires human-in-the-loop approval", 0.80),
    ESCALATE_TO_HUMAN("Escalate to on-call engineer", 0.0);

    private final String description;
    private final double minimumConfidence;

    Decision(String description, double minimumConfidence) {
        this.description = description;
        this.minimumConfidence = minimumConfidence;
    }

    public String getDescription() {
        return description;
    }

    public double getMinimumConfidence() {
        return minimumConfidence;
    }
}
