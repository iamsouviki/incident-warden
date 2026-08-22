package com.company.mcp.service;

import java.util.List;
import java.util.UUID;

/**
 * Trusted evidence supplied to the remediation planner. A free-form assistant
 * answer is never sufficient for plan eligibility: the evidence must come from
 * one or more approved procedures in the active tenant.
 */
public record SopEvidence(
        boolean serviceAvailable,
        boolean tenantScoped,
        List<UUID> procedureIds,
        String excerpt,
        double reliability,
        String reason,
        /**
         * The concrete tool invocation the approved procedure authorises, e.g.
         * {@code RESTART_SERVICE:spooler:windows}. Blank when no procedure matched.
         *
         * The executor runs this and nothing else. Authority to act comes from the
         * approved row, not from the keyword classifier, which only names a category.
         */
        String approvedActionKey
) {
    public SopEvidence {
        procedureIds = procedureIds == null ? List.of() : List.copyOf(procedureIds);
        excerpt = excerpt == null ? "" : excerpt;
        reason = reason == null ? "" : reason;
        approvedActionKey = approvedActionKey == null ? "" : approvedActionKey;
        reliability = Math.max(0.0, Math.min(1.0, reliability));
    }

    /** Evidence with no executable action attached — the planner can still assess it. */
    public SopEvidence(boolean serviceAvailable, boolean tenantScoped, List<UUID> procedureIds,
                       String excerpt, double reliability, String reason) {
        this(serviceAvailable, tenantScoped, procedureIds, excerpt, reliability, reason, "");
    }

    public boolean approvedEvidencePresent() {
        return serviceAvailable && tenantScoped && !procedureIds.isEmpty() && !excerpt.isBlank() && reliability > 0.0;
    }

    public static SopEvidence unavailable(String reason) {
        return new SopEvidence(false, false, List.of(), "", 0.0, reason);
    }

    public static SopEvidence noMatch(String reason) {
        return new SopEvidence(true, true, List.of(), "", 0.0, reason);
    }
}
