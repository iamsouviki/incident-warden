package com.company.mcp.domain;

/**
 * Incident processing lifecycle status.
 */
public enum IncidentStatus {
    PENDING("Waiting to be processed"),
    PROCESSING("Currently being processed by agents"),
    AUTO_RESOLVED("Automatically resolved without human intervention"),
    HITL_PENDING("Awaiting human approval"),
    HITL_APPROVED("Human approved SOP execution"),
    HITL_REJECTED("Human rejected auto-resolution, escalated"),
    ESCALATED("Escalated to on-call engineer"),
    AUTO_RESOLVE_FAILED("Attempted auto-resolution failed"),
    PROCESSING_FAILED("Processing pipeline failed"),
    GUARDRAILS_BLOCKED("Blocked by guardrails safety checks"),
    DEFERRED("Deferred for later processing");

    private final String description;

    IncidentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
