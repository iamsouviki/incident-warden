package com.company.warden.service;

import com.company.warden.config.CurrentUser;
import com.company.warden.dto.NormalizedIncidentRequest;
import com.company.warden.model.Incident;
import com.company.warden.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A CSV row and a pushed ticket arrive through the same method and get the same treatment:
 * a ticket, and nothing that runs. This pins the row count through the parser — a silently
 * truncated import is a set of incidents nobody is looking at.
 */
class IncidentIntakeBulkTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentService incidentService = mock(IncidentService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AuditService audit = mock(AuditService.class);

    private final IncidentIntakeService intake =
            new IncidentIntakeService(incidents, incidentService, currentUser, audit);

    @BeforeEach
    void stubs() {
        when(currentUser.username()).thenReturn("analyst");
        when(incidents.findFirstByExternalSourceAndExternalId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(incidentService.createIncident(any())).thenAnswer(call -> {
            Incident incident = call.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });
    }

    @Test
    void aTicketPushedByAThirdPartySystemIsCreatedAndWaitsForAPlan() {
        intake.ingest(new NormalizedIncidentRequest("ServiceNow", "INC001", "Printer offline on floor 2",
                "The shared printer stopped responding after a power cut.", "P3", "Hardware", "", "",
                "user@company.com"));

        verify(incidentService).createIncident(any());
    }

    /**
     * One upload must not become hundreds of actions. It cannot now for a structural reason
     * rather than a flag: creating an incident has no path that runs anything, so this asserts
     * what is left to assert — every row becomes a ticket, and tickets are all it becomes.
     */
    @Test
    void everyRowOfABulkImportBecomesATicketAndNothingMore() {
        String csv = """
                source_reference,subject,description,priority
                INC101,Printer offline on floor 2,The shared printer stopped responding.,P3
                INC102,Printer offline on floor 3,The shared printer stopped responding.,P3
                INC103,VPN drops every few minutes,Users are disconnected from the VPN.,P3
                """;
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        intake.importFile(file, "ServiceNow");

        verify(incidentService, times(3)).createIncident(any());
    }
}
