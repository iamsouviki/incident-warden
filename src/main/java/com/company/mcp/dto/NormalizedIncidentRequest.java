package com.company.mcp.dto;

public record NormalizedIncidentRequest(
        String sourceSystem,
        String sourceReference,
        String subject,
        String description,
        String priority,
        String category,
        String target,
        String severity
) {
}
