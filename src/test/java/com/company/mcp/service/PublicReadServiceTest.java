package com.company.mcp.service;

import com.company.mcp.model.SystemConfig;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This is the only place in the application where data leaves without a caller attached, so
 * it is the one that gets a test. Two invariants:
 *
 *   1. the anonymous row never grows a sensitive field, and
 *   2. an admin who turns the surface off actually turns it off.
 */
class PublicReadServiceTest {

    private final SystemConfigRepository config = mock(SystemConfigRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final PublicReadService service = new PublicReadService(config, incidents);

    /**
     * The redaction boundary, asserted on the type rather than on one response body. Adding
     * {@code description}, {@code assignee}, {@code targetHost} or {@code reporterEmail} to
     * the public row fails here — which is the point, because it would not fail anywhere else.
     */
    @Test
    void publicRowExposesOnlyTheSixAgreedFields() {
        Set<String> exposed = Arrays.stream(PublicReadService.Row.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(Set.of("externalId", "subject", "description", "status", "priority", "updatedAt"), exposed);
    }

    @Test
    void maskSensitiveRedactsIpsEmailsCredentialsAndCards() {
        String input = "Host at 192.168.1.50 reported user john.doe@company.com with password: mySecretPassword123! Card: 4111-2222-3333-4444 Phone: (555) 123-4567";
        String masked = PublicReadService.maskSensitive(input);
        assertFalse(masked.contains("192.168.1.50"));
        assertFalse(masked.contains("john.doe@company.com"));
        assertFalse(masked.contains("mySecretPassword123!"));
        assertFalse(masked.contains("4111-2222-3333-4444"));
        assertFalse(masked.contains("(555) 123-4567"));
        assertTrue(masked.contains("****"));
    }

    @Test
    void openCountExcludesFinishedWorkAndTreatsUnknownStatusesAsOpen() {
        when(config.findById(anyString())).thenReturn(Optional.empty());
        when(incidents.countGroupedByStatus(PublicReadService.DEFAULT_TENANT)).thenReturn(List.of(
                new Object[]{"New", 3L}, new Object[]{"In Progress", 2L},
                new Object[]{"Resolved", 4L}, new Object[]{"Closed", 1L},
                new Object[]{"Waiting on vendor", 5L}));
        when(incidents.countGroupedByPriority(PublicReadService.DEFAULT_TENANT))
                .thenReturn(List.<Object[]>of(new Object[]{"P1", 1L}));
        when(incidents.findLastUpdatedAt(anyString())).thenReturn(OffsetDateTime.now());

        PublicReadService.Stats stats = service.stats();
        assertEquals(15L, stats.total());
        // 3 + 2 + 5: an unrecognised status is open, never silently counted as done.
        assertEquals(10L, stats.openCount());
    }

    @Test
    void searchIsBoundedToTwentyRows() {
        when(config.findById(anyString())).thenReturn(Optional.empty());
        when(incidents.searchPublicRows(anyString(), anyString(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"INC000000042", "POS offline", "POS terminal down at 10.0.0.1", "New", "P1", OffsetDateTime.now()}));
        assertEquals(1, service.search("pos").size());
        assertEquals("INC000000042", service.search("pos").get(0).externalId());
        assertEquals("POS terminal down at ****", service.search("pos").get(0).description());
    }

    @Test
    void disabledByAnAdminMeansDisabled() {
        when(config.findById(PublicReadService.ENABLED_KEY))
                .thenReturn(Optional.of(new SystemConfig(PublicReadService.ENABLED_KEY, "false")));
        assertFalse(service.enabled());

        when(config.findById(PublicReadService.ENABLED_KEY)).thenReturn(Optional.empty());
        assertTrue(service.enabled(), "absent config means the front door is open, as documented");
    }
}
