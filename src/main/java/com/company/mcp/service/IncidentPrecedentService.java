package com.company.mcp.service;

import com.company.mcp.model.ActionExecution;
import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentComment;
import com.company.mcp.model.RemediationPlan;
import com.company.mcp.repository.ActionExecutionRepository;
import com.company.mcp.repository.IncidentCommentRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.RemediationPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Answers "have we fixed this before, and what fixed it?"
 *
 * The SOP matcher asks whether a written procedure covers an incident. This asks the
 * other question the platform has always claimed to ask and never did: whether a past
 * ticket in this tenant said the same thing, and was actually resolved by a specific
 * saved tool that a human approved and watched succeed.
 *
 * The bar for being a precedent is deliberately narrow, because the auto-run lane draws
 * its authority from exactly this record:
 *
 *   - the past execution must have status SUCCEEDED — not simulated, not dry-run;
 *   - it must carry a {@code hitlRequestId}, i.e. a person approved that exact plan;
 *   - its plan must have pinned a parseable action key, so there is something repeatable
 *     rather than a description of something that was done by hand.
 *
 * Matching uses the incident's own words plus the notes left on the past ticket, scored
 * by {@link TextSimilarity}. Term coverage rather than embeddings: see that class for
 * why an unattended decision needs a reproducible, quotable justification.
 */
@Service
public class IncidentPrecedentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentPrecedentService.class);

    /**
     * Chars of resolution notes per past incident that take part in matching.
     *
     * ponytail: a flat clip, not a summariser. Notes are what make a past ticket
     * recognisable, but an unbounded comment thread would dilute coverage into
     * meaninglessness — every query term eventually appears in a long enough thread.
     * Raise it if real tickets carry longer useful notes than this.
     */
    private static final int NOTES_BUDGET = 2000;

    /** What a reviewer is shown of the past ticket's own resolution note. */
    private static final int NOTE_EXCERPT = 600;

    private final ActionExecutionRepository executions;
    private final RemediationPlanRepository plans;
    private final IncidentRepository incidents;
    private final IncidentCommentRepository comments;
    private final ObjectMapper json;

    public IncidentPrecedentService(ActionExecutionRepository executions, RemediationPlanRepository plans,
                                    IncidentRepository incidents, IncidentCommentRepository comments,
                                    ObjectMapper json) {
        this.executions = executions;
        this.plans = plans;
        this.incidents = incidents;
        this.comments = comments;
        this.json = json;
    }

    /**
     * The closest past incident this tenant proved it can fix, or empty.
     *
     * Empty is the normal answer on a new deployment and must stay cheap and honest:
     * every caller treats "no precedent" as "no extra confidence and no autonomy".
     */
    public Optional<Precedent> findPrecedent(String tenantId, Incident incident) {
        if (tenantId == null || tenantId.isBlank() || incident == null) return Optional.empty();
        Set<String> query = TextSimilarity.tokenize(safe(incident.getSubject()) + " " + safe(incident.getDescription()));
        if (query.isEmpty()) return Optional.empty();

        List<ActionExecution> successes = executions
                .findTop100ByTenantIdAndStatusAndHitlRequestIdIsNotNullOrderByCompletedAtDesc(tenantId, "SUCCEEDED");
        if (successes.isEmpty()) return Optional.empty();

        // Newest success per past incident. An incident remediated three times is one
        // precedent, not three, and the most recent run is the one whose script is
        // still current.
        Map<UUID, ActionExecution> newestPerIncident = new LinkedHashMap<>();
        for (ActionExecution execution : successes) {
            if (execution.getIncidentId() == null) continue;
            if (execution.getIncidentId().equals(incident.getId())) continue;   // never cite itself
            newestPerIncident.putIfAbsent(execution.getIncidentId(), execution);
        }
        if (newestPerIncident.isEmpty()) return Optional.empty();

        // Three bounded fetches, never one per candidate: this runs while a user waits
        // for their ticket to be saved.
        Map<UUID, Incident> pastIncidents = new HashMap<>();
        for (Incident past : incidents.findAllById(newestPerIncident.keySet())) {
            if (tenantId.equals(past.getTenantId())) pastIncidents.put(past.getId(), past);
        }
        Map<UUID, RemediationPlan> pastPlans = new HashMap<>();
        for (RemediationPlan plan : plans.findAllById(
                newestPerIncident.values().stream().map(ActionExecution::getPlanId).toList())) {
            if (tenantId.equals(plan.getTenantId())) pastPlans.put(plan.getId(), plan);
        }
        Map<UUID, List<String>> notes = notesByIncident(pastIncidents.keySet());

        Precedent best = null;
        for (Map.Entry<UUID, ActionExecution> entry : newestPerIncident.entrySet()) {
            Incident past = pastIncidents.get(entry.getKey());
            RemediationPlan plan = pastPlans.get(entry.getValue().getPlanId());
            if (past == null || plan == null) continue;

            String actionKey = stringField(plan.getParametersJson(), "approvedActionKey");
            if (actionKey.isBlank()) continue;   // nothing repeatable was pinned into that plan

            List<String> incidentNotes = notes.getOrDefault(past.getId(), List.of());
            Set<String> candidate = TextSimilarity.tokenize(safe(past.getSubject()) + " "
                    + safe(past.getDescription()) + " " + clip(String.join(" ", incidentNotes), NOTES_BUDGET));
            List<String> matched = TextSimilarity.matched(query, candidate);
            double similarity = TextSimilarity.coverage(query, candidate);
            if (best != null && similarity <= best.similarity()) continue;

            best = new Precedent(past.getId(), reference(past), plan.getId(), plan.getActionName(), actionKey,
                    plan.getRemediationScript() == null ? "" : plan.getRemediationScript(),
                    plan.getScriptLanguage() == null ? "" : plan.getScriptLanguage(),
                    plan.getScriptSource() == null ? "NONE" : plan.getScriptSource(),
                    plan.getSopEvidence() == null ? "" : plan.getSopEvidence(),
                    procedureIds(plan.getParametersJson()),
                    similarity, List.copyOf(matched),
                    clip(incidentNotes.isEmpty() ? "" : incidentNotes.get(0), NOTE_EXCERPT),
                    entry.getValue().getCompletedAt(),
                    // Carried, not judged. This service stays "find the closest past fix" so
                    // the HITL reviewer can still be shown a fix from another store as
                    // advice; whether the store matches closely enough to act without asking
                    // is a decision about authority, and that lives in AutoRemediationService.
                    IncidentTarget.store(past), IncidentTarget.connection(past));
        }

        if (best == null) return Optional.empty();
        log.debug("[PRECEDENT] Incident {} best match {} similarity {} on {}",
                incident.getId(), best.reference(), best.similarity(), best.matchedTerms());
        return Optional.of(best);
    }

    /** Newest-first notes per incident, so {@code get(0)} is the latest thing anyone said. */
    private Map<UUID, List<String>> notesByIncident(Set<UUID> incidentIds) {
        Map<UUID, List<String>> byIncident = new HashMap<>();
        if (incidentIds.isEmpty()) return byIncident;
        for (IncidentComment comment : comments.findByIncidentIdInOrderByCreatedAtDesc(incidentIds)) {
            if (comment.getCommentText() == null || comment.getCommentText().isBlank()) continue;
            byIncident.computeIfAbsent(comment.getIncidentId(), key -> new ArrayList<>()).add(comment.getCommentText());
        }
        return byIncident;
    }

    private String stringField(String parametersJson, String field) {
        try {
            Object value = json.readValue(parametersJson, Map.class).get(field);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<UUID> procedureIds(String parametersJson) {
        try {
            Object ids = json.readValue(parametersJson, Map.class).get("procedureIds");
            if (!(ids instanceof List<?> list)) return List.of();
            List<UUID> result = new ArrayList<>();
            for (Object id : list) {
                try { result.add(UUID.fromString(String.valueOf(id))); } catch (IllegalArgumentException ignored) {}
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String reference(Incident incident) {
        return incident.getExternalId() == null || incident.getExternalId().isBlank()
                ? String.valueOf(incident.getId()) : incident.getExternalId();
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /**
     * One past incident this tenant fixed, and the tool that fixed it.
     *
     * @param actionKey    the tool invocation a human approved, e.g. {@code RESTART_SERVICE:spooler:windows}
     * @param scriptSource SOP_TEMPLATE | SOP_GROUNDED | LLM_KNOWLEDGE | NONE — how much
     *                     authority stood behind the script. The auto-run lane refuses the
     *                     last two: nothing but the one reviewer ever vouched for them.
     * @param matchedTerms the words shared with the new incident, so the decision can be
     *                     quoted rather than merely scored
     * @param storeNumber  the store the past fix was proven at, "" for a non-store incident.
     *                     The auto-run lane will only inherit this approval for the same store.
     * @param connectionMethod how the executor reached that host, "" if its default path worked
     */
    public record Precedent(UUID incidentId, String reference, UUID planId, String actionName, String actionKey,
                            String script, String scriptLanguage, String scriptSource, String sopEvidence,
                            List<UUID> procedureIds, double similarity, List<String> matchedTerms,
                            String resolutionNote, OffsetDateTime resolvedAt,
                            String storeNumber, String connectionMethod) {

        /** The evidence record the past plan was built on, for re-running the guardrails. */
        public SopEvidence asEvidence() {
            return new SopEvidence(true, true, procedureIds, sopEvidence, 1.0,
                    "PRECEDENT_APPROVED_EXECUTION:" + reference, actionKey);
        }
    }
}
