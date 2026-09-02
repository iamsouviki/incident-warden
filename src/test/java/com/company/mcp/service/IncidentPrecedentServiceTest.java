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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The precedent matcher decides whether the platform believes it has already been given
 * permission to fix something. Everything the auto-run lane does rests on these answers,
 * so each rule that narrows what counts as a precedent is pinned here.
 */
class IncidentPrecedentServiceTest {

    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final RemediationPlanRepository plans = mock(RemediationPlanRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentCommentRepository comments = mock(IncidentCommentRepository.class);

    private final IncidentPrecedentService service =
            new IncidentPrecedentService(executions, plans, incidents, comments, new ObjectMapper());

    /** Newest first, matching the repository's ordering contract. */
    private final List<ActionExecution> approvedSuccesses = new ArrayList<>();
    private final List<Incident> pastIncidents = new ArrayList<>();
    private final List<RemediationPlan> pastPlans = new ArrayList<>();
    private final List<IncidentComment> pastNotes = new ArrayList<>();

    @BeforeEach
    void wireRepositories() {
        when(executions.findTop100ByStatusAndHitlRequestIdIsNotNullOrderByCompletedAtDesc("SUCCEEDED"))
                .thenReturn(approvedSuccesses);
        when(incidents.findAllById(any())).thenAnswer(call -> {
            Set<UUID> ids = ids(call.getArgument(0));
            return pastIncidents.stream().filter(i -> ids.contains(i.getId())).toList();
        });
        when(plans.findAllById(any())).thenAnswer(call -> {
            Set<UUID> ids = ids(call.getArgument(0));
            return pastPlans.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(comments.findByIncidentIdInOrderByCreatedAtDesc(any())).thenAnswer(call -> {
            Set<UUID> ids = new HashSet<>(call.getArgument(0));
            return pastNotes.stream().filter(c -> ids.contains(c.getIncidentId())).toList();
        });
    }

    @Test
    void withNoHumanApprovedSuccessThereIsNoPrecedent() {
        Optional<IncidentPrecedentService.Precedent> found = service.findPrecedent(newIncident());

        assertTrue(found.isEmpty());
        // The query itself is the human-approval filter: SUCCEEDED, and hitl_request_id
        // present. If this call ever loosens, an auto-run becomes its own authority.
        verify(executions).findTop100ByStatusAndHitlRequestIdIsNotNullOrderByCompletedAtDesc("SUCCEEDED");
    }

    @Test
    void theClosestWordingWins() {
        resolved("Printer queue jammed", "", "CLEAR_CACHE:printer:spool:linux", null, "SOP_TEMPLATE");
        UUID exact = resolved("Printer queue stuck", "", "RESTART_SERVICE:spooler:windows", null, "SOP_TEMPLATE");

        IncidentPrecedentService.Precedent best = service.findPrecedent(newIncident()).orElseThrow();

        assertEquals(exact, best.incidentId());
        assertEquals("RESTART_SERVICE:spooler:windows", best.actionKey());
        assertEquals(1.0, best.similarity(), 0.001);
        assertTrue(best.matchedTerms().containsAll(List.of("printer", "queue", "stuck")));
    }

    /**
     * The half of "old incident notes/history" that the SOP matcher cannot see. A ticket
     * whose title says nothing recognisable is still the same ticket if the analyst wrote
     * down what it actually was.
     */
    @Test
    void resolutionNotesCountAsPartOfThePastIncident() {
        UUID vague = resolved("Spooler fault", "Reported by floor two",
                "RESTART_SERVICE:spooler:windows", "Printer queue stuck again; restarted the spooler.", "SOP_TEMPLATE");

        IncidentPrecedentService.Precedent best = service.findPrecedent(newIncident()).orElseThrow();

        assertEquals(vague, best.incidentId());
        assertEquals(1.0, best.similarity(), 0.001);
        assertTrue(best.resolutionNote().contains("restarted the spooler"));
    }

    @Test
    void anIncidentIsNeverItsOwnPrecedent() {
        Incident subject = newIncident();
        // Same incident, already remediated once under approval. Citing it would let a
        // second look at the same ticket act on the strength of the first.
        pastIncidents.add(subject);
        RemediationPlan plan = plan(subject.getId(), "RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE");
        approvedSuccesses.add(execution(subject.getId(), plan.getId()));

        assertTrue(service.findPrecedent(subject).isEmpty());
    }

    /** A plan with no pinned action key describes a fix; it does not contain one. */
    @Test
    void aPlanThatPinnedNoActionKeyIsNotRepeatable() {
        UUID incidentId = UUID.randomUUID();
        pastIncidents.add(Incident.builder().id(incidentId)
                .subject("Printer queue stuck").externalId("INC-9").build());
        RemediationPlan plan = new RemediationPlan();
        ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
        plan.setIncidentId(incidentId);
        plan.setActionName("clear-printer-queue");
        plan.setParametersJson("{\"classification\":\"PRINTING\"}");
        pastPlans.add(plan);
        approvedSuccesses.add(execution(incidentId, plan.getId()));

        assertTrue(service.findPrecedent(newIncident()).isEmpty());
    }

    /**
     * One incident remediated twice is one precedent. The newest run is the one whose
     * script reflects current practice, so it is the one that may be repeated.
     */
    @Test
    void theNewestSuccessPerPastIncidentIsTheOneCited() {
        UUID incidentId = UUID.randomUUID();
        pastIncidents.add(Incident.builder().id(incidentId)
                .subject("Printer queue stuck").externalId("INC-7").build());
        RemediationPlan newer = plan(incidentId, "RESTART_SERVICE:spooler:windows", "SOP_TEMPLATE");
        RemediationPlan older = plan(incidentId, "RESTART_SERVICE:oldspooler:windows", "SOP_TEMPLATE");
        approvedSuccesses.add(execution(incidentId, newer.getId()));   // repository order: newest first
        approvedSuccesses.add(execution(incidentId, older.getId()));

        IncidentPrecedentService.Precedent best = service.findPrecedent(newIncident()).orElseThrow();

        assertEquals("RESTART_SERVICE:spooler:windows", best.actionKey());
    }

    private Incident newIncident() {
        return Incident.builder().id(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
                .subject("Printer queue stuck").description("")
                .priority("P3").externalId("INC-NEW").build();
    }

    private UUID resolved(String subject, String description, String actionKey, String note, String scriptSource) {
        UUID incidentId = UUID.randomUUID();
        pastIncidents.add(Incident.builder().id(incidentId).subject(subject)
                .description(description).externalId("INC-" + pastIncidents.size()).build());
        RemediationPlan plan = plan(incidentId, actionKey, scriptSource);
        approvedSuccesses.add(execution(incidentId, plan.getId()));
        if (note != null) {
            pastNotes.add(new IncidentComment(UUID.randomUUID(), incidentId, "analyst", note, OffsetDateTime.now()));
        }
        return incidentId;
    }

    private RemediationPlan plan(UUID incidentId, String actionKey, String scriptSource) {
        RemediationPlan plan = new RemediationPlan();
        ReflectionTestUtils.setField(plan, "id", UUID.randomUUID());
        plan.setIncidentId(incidentId);
        plan.setActionName("restart-approved-service");
        plan.setParametersJson("{\"approvedActionKey\":\"" + actionKey + "\",\"procedureIds\":[]}");
        plan.setRemediationScript("systemctl restart 'spooler'");
        plan.setScriptLanguage("bash");
        plan.setScriptSource(scriptSource);
        pastPlans.add(plan);
        return plan;
    }

    private ActionExecution execution(UUID incidentId, UUID planId) {
        ActionExecution execution = new ActionExecution();
        ReflectionTestUtils.setField(execution, "id", UUID.randomUUID());
        execution.setIncidentId(incidentId);
        execution.setPlanId(planId);
        execution.setHitlRequestId(UUID.randomUUID());   // a person approved this one
        execution.setStatus("SUCCEEDED");
        execution.setMode("LIVE");
        execution.setCompletedAt(OffsetDateTime.now());
        return execution;
    }

    private static Set<UUID> ids(Iterable<UUID> iterable) {
        Set<UUID> ids = new HashSet<>();
        iterable.forEach(ids::add);
        return ids;
    }
}
