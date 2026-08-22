package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.Incident;
import com.company.mcp.model.IncidentHistory;
import com.company.mcp.repository.ExternalIncidentRepository;
import com.company.mcp.repository.IncidentHistoryRepository;
import com.company.mcp.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every form in the UI PUTs to one endpoint, so the merge rule in
 * {@code updateIncidentFields} is the only thing standing between a partial form submit and
 * silent data loss. The bug that prompted these tests: answering "which server?" reverted a
 * status the remediation lane had set moments earlier, because an unmentioned field was
 * treated as an instruction to null it.
 */
class IncidentUpdateTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final ExternalIncidentRepository externals = mock(ExternalIncidentRepository.class);
    private final IncidentHistoryRepository history = mock(IncidentHistoryRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);

    private final IncidentService service = new IncidentService();
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void wireByHand() {
        // Field injection, so the mocks go in the same way Spring would put the real beans in.
        ReflectionTestUtils.setField(service, "incidentRepository", incidents);
        ReflectionTestUtils.setField(service, "externalIncidentRepository", externals);
        ReflectionTestUtils.setField(service, "incidentHistoryRepository", history);
        ReflectionTestUtils.setField(service, "notificationService", notifications);
        ReflectionTestUtils.setField(service, "currentUser", currentUser);
        when(currentUser.tenantId()).thenReturn("tenant-a");
        when(incidents.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
    }

    /** The reported bug: a three-field save must not carry the rest of the ticket with it. */
    @Test
    void aFieldThisPutNeverMentionedIsLeftAlone() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setTargetHost("store-0042-app-01");

        Incident saved = service.updateIncident(id, patch, "analyst");

        assertEquals("store-0042-app-01", saved.getTargetHost());
        assertEquals("ESCALATED", saved.getStatus());          // set by the remediation lane, not reverted
        assertEquals("Printer queue stuck", saved.getSubject());
        assertEquals("P2", saved.getPriority());
        assertEquals("0042", saved.getStoreNumber());
    }

    /** Clearing a field still has to be possible, so "" and null cannot mean the same thing. */
    @Test
    void anEmptyStringClearsTheFieldItNames() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setConnectionMethod("");                          // back to the executor's default path

        assertEquals("", service.updateIncident(id, patch, "analyst").getConnectionMethod());
    }

    /** No diff, no history row, no mail: a re-save of unchanged values is not an event. */
    @Test
    void aPutThatChangesNothingIsSilent() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setStatus("ESCALATED");
        patch.setTargetHost("store-0042-pos-01");

        service.updateIncident(id, patch, "analyst");

        verify(history, never()).save(any(IncidentHistory.class));
        verify(notifications).notifyIncidentUpdated(any(), eqEmpty(), anyString());
    }

    /** Each changed field leaves its own audit row, because "who pointed it there" is the question. */
    @Test
    void everyChangedFieldIsHistoried() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setStoreNumber("0099");
        patch.setTargetHost("store-0099-pos-01");

        service.updateIncident(id, patch, "analyst");

        org.mockito.ArgumentCaptor<IncidentHistory> rows = org.mockito.ArgumentCaptor.forClass(IncidentHistory.class);
        verify(history, org.mockito.Mockito.times(2)).save(rows.capture());
        assertTrue(rows.getAllValues().stream().anyMatch(r -> "store_number".equals(r.getFieldName())));
        assertTrue(rows.getAllValues().stream().anyMatch(r -> "target_host".equals(r.getFieldName())));
    }

    private static List<String> eqEmpty() {
        return org.mockito.ArgumentMatchers.argThat(List::isEmpty);
    }

    private Incident existing() {
        Incident incident = Incident.builder().id(id).tenantId("tenant-a")
                .subject("Printer queue stuck").description("Nothing prints")
                .priority("P2").status("ESCALATED").externalId("INC000000006")
                .assignee("analyst").assignedGteam("Store Ops")
                .storeNumber("0042").targetHost("store-0042-pos-01")
                .build();
        incident.setConnectionMethod("SSH");
        return incident;
    }
}
