package com.company.warden.dto;

public record NormalizedIncidentRequest(
        String sourceSystem,
        String sourceReference,
        String subject,
        String description,
        String priority,
        String category,
        String target,
        String severity,
        /**
         * The requester's address as the source system recorded it. Untrusted: it comes from
         * an uploaded file, so NotificationService validates it before use and skips it if
         * malformed rather than rejecting the whole incident.
         */
        String reporterEmail
) {
}
