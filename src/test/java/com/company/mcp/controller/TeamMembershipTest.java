package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.model.Team;
import com.company.mcp.model.TeamEmployee;
import com.company.mcp.repository.TeamEmployeeRepository;
import com.company.mcp.repository.TeamRepository;
import com.company.mcp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The roster is not a display list: it is what turns an incident's assignee username into
 * an email address. So the checks that matter are the ones that stop a row existing that
 * looks like membership but cannot be notified, and the ones that keep username unique —
 * team_employees.username is unique table-wide, so an "add" of somebody already on another
 * team has to move them rather than insert a second row.
 */
class TeamMembershipTest {

    private static final UUID IT_OPS = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
    private static final UUID SEC_OPS = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12");

    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamEmployeeRepository members = mock(TeamEmployeeRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TeamController controller = new TeamController(teams, members, users);

    private final Team itOps = new Team(IT_OPS, "IT Ops", "Information Technology Operations");
    private final Team secOps = new Team(SEC_OPS, "SecOps", "Security Operations");

    TeamMembershipTest() {
        when(teams.findById(IT_OPS)).thenReturn(Optional.of(itOps));
        when(teams.findById(SEC_OPS)).thenReturn(Optional.of(secOps));
        when(members.findByUsername(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void anAddressIsResolvedFromTheLoginTableWhenTheAdminOmitsOne() {
        when(users.findByUsername("john_ops")).thenReturn(Optional.of(user("john.ops@company.com")));

        ResponseEntity<?> response = controller.addMember(IT_OPS, Map.of("username", "john_ops"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<TeamEmployee> saved = ArgumentCaptor.forClass(TeamEmployee.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("john.ops@company.com");
        assertThat(saved.getValue().getTeam()).isSameAs(itOps);
        assertThat(saved.getValue().getId()).isNotNull();   // no DB generator on this column
    }

    /** A typo'd username with no login behind it would be a member nobody can reach. */
    @Test
    void anUnknownUsernameWithNoAddressIsRefused() {
        ResponseEntity<?> response = controller.addMember(IT_OPS, Map.of("username", "jhon_ops"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString()).contains("can never be notified");
        verify(members, never()).save(any());
    }

    @Test
    void aMalformedAddressIsRefused() {
        ResponseEntity<?> response = controller.addMember(IT_OPS, Map.of("username", "new_hire", "email", "not-an-address"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(members, never()).save(any());
    }

    /**
     * Moving a member must not require their address to be typed again: their own roster row
     * already holds one, and it outranks auth.users precisely because a seeded roster member
     * (alice_sec here) may have no login at all. Refusing this was a real bug — the "member
     * with no address" guard fired on somebody who had one.
     */
    @Test
    void addingSomebodyAlreadyOnAnotherTeamMovesThemAndKeepsTheirAddress() {
        TeamEmployee existing = new TeamEmployee(UUID.randomUUID(), "alice_sec", "alice.sec@company.com", secOps);
        when(members.findByUsername("alice_sec")).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = controller.addMember(IT_OPS, Map.of("username", "alice_sec", "email", ""));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) response.getBody()).get("movedFrom")).isEqualTo("SecOps");
        ArgumentCaptor<TeamEmployee> saved = ArgumentCaptor.forClass(TeamEmployee.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(existing.getId());   // same row, new team
        assertThat(saved.getValue().getTeam()).isSameAs(itOps);
        assertThat(saved.getValue().getEmail()).isEqualTo("alice.sec@company.com");
    }

    /** A typed address is the admin's decision and outranks both stored sources. */
    @Test
    void aTypedAddressWinsOverTheStoredOnes() {
        when(members.findByUsername("john_ops")).thenReturn(
                Optional.of(new TeamEmployee(UUID.randomUUID(), "john_ops", "old.address@company.com", itOps)));
        when(users.findByUsername("john_ops")).thenReturn(Optional.of(user("login.address@company.com")));

        controller.addMember(IT_OPS, Map.of("username", "john_ops", "email", "john.ops@company.com"));

        ArgumentCaptor<TeamEmployee> saved = ArgumentCaptor.forClass(TeamEmployee.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("john.ops@company.com");
    }

    /** A stale page must not be able to remove somebody from a team they already left. */
    @Test
    void removingFromTheWrongTeamIsRefused() {
        when(members.findByUsername("alice_sec")).thenReturn(
                Optional.of(new TeamEmployee(UUID.randomUUID(), "alice_sec", "alice.sec@company.com", secOps)));

        ResponseEntity<?> response = controller.removeMember(IT_OPS, "alice_sec");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(members, never()).delete(any());
    }

    @Test
    void removingFromTheRightTeamDeletesTheRosterRow() {
        TeamEmployee existing = new TeamEmployee(UUID.randomUUID(), "john_ops", "john.ops@company.com", itOps);
        when(members.findByUsername("john_ops")).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = controller.removeMember(IT_OPS, "john_ops");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(members).delete(existing);
    }

    @Test
    void aTeamThatDoesNotExistCannotGainMembers() {
        ResponseEntity<?> response = controller.addMember(UUID.randomUUID(),
                Map.of("username", "new_hire", "email", "new.hire@company.com"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(members, never()).save(any());
    }

    private static AppUser user(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        return user;
    }
}
