package com.company.mcp.service;

import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the approved procedure that authorises a remediation.
 *
 * This replaces a mock that returned fabricated evidence with {@code
 * approvedEvidencePresent = true} for every incident. Because the whole safety chain
 * keys off that flag, the mock meant NO_APPROVED_SOP_EVIDENCE could never fire and any
 * incident looked SOP-backed. A real lookup that can return "no match" is the point.
 *
 * ponytail: keyword overlap scored in Java over the approved rows. An approved
 * SOP set is operator-curated and small (tens to low hundreds). Past a few thousand
 * rows, move the scoring into Postgres full-text search or pgvector — the return type
 * stays the same, so only this class changes.
 */
@Service
public class SopProcedureService {
    private static final Logger log = LoggerFactory.getLogger(SopProcedureService.class);

    private final SopProcedureRepository procedures;

    public SopProcedureService(SopProcedureRepository procedures) {
        this.procedures = procedures;
    }

    /**
     * @return the best-matching approved procedures, or empty when nothing clears the
     *         minimum overlap.
     */
    public List<SopProcedure> findApprovedMatches(String incidentText) {
        if (incidentText == null || incidentText.isBlank()) {
            return List.of();
        }
        Set<String> queryTerms = tokenize(incidentText);
        if (queryTerms.isEmpty()) return List.of();

        List<Scored> scored = new ArrayList<>();
        for (SopProcedure procedure : procedures.findByApprovalStatus("APPROVED")) {
            double score = overlap(queryTerms, procedure);
            if (score > 0) scored.add(new Scored(procedure, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        // At least two distinct signal terms must match. One shared word ("printer") is
        // not evidence that a specific procedure applies to this incident.
        return scored.stream().filter(s -> s.score() >= 2.0).limit(5).map(Scored::procedure).toList();
    }

    /**
     * Builds the evidence record the guardrails and confidence stages consume.
     * Returns a no-match result rather than inventing evidence when nothing applies.
     */
    public SopEvidence toEvidence(String incidentText) {
        List<SopProcedure> matches = findApprovedMatches(incidentText);
        if (matches.isEmpty()) {
            log.debug("[SOP] No approved procedure matched the incident text");
            return SopEvidence.noMatch("NO_APPROVED_SOP_MATCH");
        }

        SopProcedure best = matches.get(0);
        List<UUID> ids = matches.stream().map(SopProcedure::getId).toList();
        StringBuilder excerpt = new StringBuilder("SOP: ").append(best.getTitle());
        if (best.getDescription() != null && !best.getDescription().isBlank()) {
            excerpt.append('\n').append(best.getDescription());
        }
        excerpt.append("\nApproved action: ").append(best.getActionKey());

        return new SopEvidence(true, ids, excerpt.toString(),
                best.observedSuccessRate(), "APPROVED_SOP_MATCH", best.getActionKey());
    }

    /** Records the outcome of an execution so confidence reflects reality over time. */
    public void recordOutcome(UUID procedureId, boolean success) {
        procedures.findById(procedureId).ifPresent(procedure -> {
            if (success) procedure.setSuccessCount(procedure.getSuccessCount() + 1);
            else procedure.setFailureCount(procedure.getFailureCount() + 1);
            procedure.setUpdatedAt(java.time.OffsetDateTime.now());
            procedures.save(procedure);
        });
    }

    /**
     * Overlap score. Keyword hits are weighted highest because an operator curated them
     * specifically to route incidents; title and description are weaker signals.
     *
     * Each matched term contributes once, at its strongest field weight. Summing per
     * field instead let a single shared word ("cache", appearing in both the keywords
     * and the title) reach the two-term floor on its own.
     */
    private double overlap(Set<String> queryTerms, SopProcedure procedure) {
        Map<String, Double> best = new HashMap<>();
        score(best, queryTerms, procedure.getMatchKeywords(), 1.5);
        score(best, queryTerms, procedure.getTitle(), 1.0);
        score(best, queryTerms, procedure.getDescription(), 0.5);
        double total = 0;
        for (double weight : best.values()) total += weight;
        return total;
    }

    private void score(Map<String, Double> best, Set<String> queryTerms, String field, double weight) {
        for (String term : tokenize(field)) {
            if (queryTerms.contains(term)) best.merge(term, weight, Math::max);
        }
    }

    private Set<String> tokenize(String text) {
        // Shared with the precedent matcher: one stop-word list, so an incident that
        // matches a procedure and an incident that matches a past ticket are tokenised
        // the same way.
        return TextSimilarity.tokenize(text);
    }

    private record Scored(SopProcedure procedure, double score) {}
}
