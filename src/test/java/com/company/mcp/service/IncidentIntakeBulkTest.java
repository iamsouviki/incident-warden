package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.dto.NormalizedIncidentRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One ticket may be remediated from precedent without asking anybody; five hundred rows in an
 * uploaded file may not. Both arrive through IncidentService.createIncident, so the only thing
 * separating them is the flag this test pins.
 *
 * Collapse the two createIncident overloads back into one, or drop the {@code false} in the
 * import loop, and one click on Import becomes one unattended restart per matching row. That is
 * the failure this exists to catch — it is invisible in the UI and there is no retry to undo it.
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
        when(currentUser.tenantId()).thenReturn("default");
        when(currentUser.username()).thenReturn("analyst");
        when(incidents.findFirstByTenantIdAndExternalSourceAndExternalId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(incidentService.createIncident(any(), anyBoolean())).thenAnswer(call -> {
            Incident incident = call.getArgument(0);
            incident.setId(UUID.randomUUID());
            return incident;
        });
    }

    @Test
    void aSingleTicketPushedByAThirdPartySystemMayAutoRunFromPrecedent() {
        intake.ingest(new NormalizedIncidentRequest("ServiceNow", "INC001", "Printer offline on floor 2",
                "The shared printer stopped responding after a power cut.", "P3", "Hardware", "", "",
                "user@company.com"));

        verify(incidentService).createIncident(any(), eq(true));
    }

    @Test
    void everyRowOfABulkImportIsSentToTheApprovalQueueInstead() {
        String csv = """
                source_reference,subject,description,priority
                INC101,Printer offline on floor 2,The shared printer stopped responding.,P3
                INC102,Printer offline on floor 3,The shared printer stopped responding.,P3
                INC103,VPN drops every few minutes,Users are disconnected from the VPN.,P3
                """;
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        intake.importFile(file, "ServiceNow");

        ArgumentCaptor<Boolean> considerUnattended = ArgumentCaptor.forClass(Boolean.class);
        verify(incidentService, times(3)).createIncident(any(), considerUnattended.capture());
        assertThat(considerUnattended.getAllValues()).containsOnly(false);
    }
}
