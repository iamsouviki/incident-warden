package com.company.mcp.domain;

/**
 * Incident severity levels (P1-P4).
 * P1: Critical - affecting production, many users
 * P2: High - affecting features or multiple systems
 * P3: Medium - affecting some functionality
 * P4: Low - minor issues, cosmetic issues
 */
public enum Severity {
    P1("Critical", 4),
    P2("High", 3),
    P3("Medium", 2),
    P4("Low", 1);

    private final String label;
    private final int priority;

    Severity(String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    public String getLabel() {
        return label;
    }

    public int getPriority() {
        return priority;
    }

    public static Severity fromString(String value) {
        return Severity.valueOf(value.toUpperCase());
    }
}
