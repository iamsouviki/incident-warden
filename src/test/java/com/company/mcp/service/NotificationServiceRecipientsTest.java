package com.company.mcp.service;

import com.company.mcp.model.AppUser;
import com.company.mcp.model.Incident;
import com.company.mcp.model.Team;
import com.company.mcp.model.TeamEmployee;
import com.company.mcp.repository.SystemConfigRepository;
import com.company.mcp.repository.TeamEmployeeRepository;
import com.company.mcp.repository.TeamRepository;
import com.company.mcp.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one check behind recipient resolution: three independent sources, any of which may be
 * absent, feeding an address list that must never contain a fabricated or malformed entry.
 * Pure unit test — no Spring context, no database, no mail server.
 */
class NotificationServiceRecipientsTest {

    private final SystemConfigRepository config = mock(SystemConfigRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamEmployeeRepository employees = mock(TeamEmployeeRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService service = new NotificationService(config, teams, employees, users);

    private static Incident incident(String reporterEmail, String assignee, String group) {
        Incident incident = new Incident();
        incident.setId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"));
        incident.setSubject("Printer offline");
        incident.setReporterEmail(reporterEmail);
        incident.setAssignee(assignee);
        incident.setAssignedGteam(group);
        return incident;
    }

    /** Default: nothing resolves. Each test then stubs only the source it is about. */
    private void noLookups() {
        when(employees.findByUsername(anyString())).thenReturn(Optional.empty());
        when(teams.findByName(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
    }

    private void roster(String username, String email) {
        TeamEmployee employee = new TeamEmployee();
        employee.setUsername(username);
        employee.setEmail(email);
        when(employees.findByUsername(username)).thenReturn(Optional.of(employee));
    }

    private void team(String name, String email) {
        Team team = new Team();
        team.setName(name);
        team.setEmail(email);
        when(teams.findByName(name)).thenReturn(Optional.of(team));
    }

    @Test
    void resolvesReporterAssigneeAndGroup() {
        noLookups();
        roster("john_ops", "john.ops@company.com");
        team("IT Ops", "it-ops@company.com");

        assertThat(service.recipientsFor(incident("caller@company.com", "john_ops", "IT Ops")))
                .containsExactly("caller@company.com", "john.ops@company.com", "it-ops@company.com");
    }

    @Test
    void skipsWhatItCannotResolveInsteadOfInventingIt() {
        noLookups();
        // Assignee not on any roster and not a login; team has no address: one recipient left.
        assertThat(service.recipientsFor(incident("caller@company.com", "ghost_user", "Ghost Team")))
                .containsExactly("caller@company.com");
    }

    @Test
    void fallsBackToTheLoginTableWhenTheAssigneeHasNoTeamRow() {
        noLookups();
        AppUser user = new AppUser();
        user.setUsername("admin");
        user.setEmail("Admin@Company.com");
        when(users.findByUsername("admin")).thenReturn(Optional.of(user));

        // Lower-cased, so the same person reached two ways is one recipient, not two.
        assertThat(service.recipientsFor(incident(null, "admin", null)))
                .containsExactly("admin@company.com");
    }

    @Test
    void dedupesTheSamePersonReportingAndOwning() {
        noLookups();
        roster("john_ops", "John.Ops@company.com");

        assertThat(service.recipientsFor(incident("john.ops@company.com", "john_ops", null)))
                .containsExactly("john.ops@company.com");
    }

    @Test
    void rejectsMalformedAndHeaderInjectingAddresses() {
        noLookups();
        assertThat(service.recipientsFor(incident("not-an-address", "nobody", null))).isEmpty();
        assertThat(service.recipientsFor(
                incident("ok@company.com\nBcc: attacker@evil.example", "nobody", null))).isEmpty();
    }

    @Test
    void sendsNothingWhileNotificationsAreDisabled() {
        // No config rows: notify_enabled defaults to false, so send() must be a no-op rather
        // than reaching for a relay nobody configured.
        when(config.findById(anyString())).thenReturn(Optional.empty());
        noLookups();

        assertThat(service.send(List.of("someone@company.com"), "subject", "body")).isFalse();
        assertThat(service.notifyIncidentUpdated(incident("a@b.com", null, null),
                List.of("Status: New → Closed"), "tester")).isFalse();
    }

    @Test
    void aChangelessUpdateSendsNothing() {
        // No stubbing at all: returning early means it never looked at config or recipients.
        assertThat(service.notifyIncidentUpdated(incident("a@b.com", null, null), List.of(), "tester")).isFalse();
        assertThat(service.notifyIncidentUpdated(incident("a@b.com", null, null), null, "tester")).isFalse();
    }
}
