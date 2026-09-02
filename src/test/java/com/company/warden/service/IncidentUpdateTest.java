package com.company.warden.service;

import com.company.warden.config.CurrentUser;
import com.company.warden.model.Incident;
import com.company.warden.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentUpdateTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);

    private final IncidentService service = new IncidentService();
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void wireByHand() {
        ReflectionTestUtils.setField(service, "incidentRepository", incidents);
        ReflectionTestUtils.setField(service, "notificationService", notifications);
        ReflectionTestUtils.setField(service, "currentUser", currentUser);
        when(incidents.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aFieldThisPutNeverMentionedIsLeftAlone() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setTargetHost("store-0042-app-01");

        Incident saved = service.updateIncident(id, patch, "analyst");

        assertEquals("store-0042-app-01", saved.getTargetHost());
        assertEquals("ESCALATED", saved.getStatus());
        assertEquals("Printer queue stuck", saved.getSubject());
        assertEquals("P2", saved.getPriority());
        assertEquals("0042", saved.getStoreNumber());
    }

    @Test
    void anEmptyStringClearsTheFieldItNames() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setConnectionMethod("");

        assertEquals("", service.updateIncident(id, patch, "analyst").getConnectionMethod());
    }

    @Test
    void aPutThatChangesNothingIsSilent() {
        Incident stored = existing();
        when(incidents.findById(id)).thenReturn(Optional.of(stored));

        Incident patch = new Incident();
        patch.setStatus("ESCALATED");
        patch.setTargetHost("store-0042-pos-01");

        service.updateIncident(id, patch, "analyst");

        verify(notifications).notifyIncidentUpdated(any(), eqEmpty(), anyString());
    }

    private static List<String> eqEmpty() {
        return org.mockito.ArgumentMatchers.argThat(List::isEmpty);
    }

    private Incident existing() {
        Incident incident = Incident.builder().id(id)
                .subject("Printer queue stuck").description("Nothing prints")
                .priority("P2").status("ESCALATED").externalId("INC000000006")
                .assignee("analyst").assignedGteam("Store Ops")
                .storeNumber("0042").targetHost("store-0042-pos-01")
                .build();
        incident.setConnectionMethod("SSH");
        return incident;
    }
}
