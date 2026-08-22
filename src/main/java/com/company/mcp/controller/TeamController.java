package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.model.Team;
import com.company.mcp.model.TeamEmployee;
import com.company.mcp.repository.TeamEmployeeRepository;
import com.company.mcp.repository.TeamRepository;
import com.company.mcp.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    /** Same shape NotificationService accepts, so the UI cannot store an address that is silently never used. */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s<>@,;:\\\\\"]+@[A-Za-z0-9._-]+\\.[A-Za-z]{2,}$");

    private final TeamRepository teamRepository;
    private final TeamEmployeeRepository memberRepository;
    private final UserRepository userRepository;

    public TeamController(TeamRepository teamRepository, TeamEmployeeRepository memberRepository,
                          UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<Team>> getTeams() {
        // Sorted because saving a team's mail id rewrites its row, and an unordered findAll
        // then reshuffles the list under the admin who just clicked Save.
        return ResponseEntity.ok(teamRepository.findAll(Sort.by("name")));
    }

    /**
     * Sets the group's distribution address — the only team field that is configuration
     * rather than org structure, and the one recipient of an incident notification that
     * cannot be derived from existing rows. ADMIN only (see SecurityConfig): whoever owns
     * this field decides where automated action reports land.
     *
     * An empty string clears it, which is how an admin turns group notification off for
     * one team without touching the global switch.
     */
    @PutMapping("/{id}/email")
    public ResponseEntity<?> setTeamEmail(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim();
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a valid email address."));
        }
        return teamRepository.findById(id)
                .<ResponseEntity<?>>map(team -> {
                    team.setEmail(email.isEmpty() ? null : email);
                    teamRepository.save(team);
                    return ResponseEntity.ok(Map.of("id", team.getId().toString(), "email", email));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Team not found.")));
    }

    /**
     * Puts a person on a team's roster — or moves them onto it. team_employees.username is
     * unique table-wide, so one person belongs to exactly one team and "add" and "move" are
     * the same write; a second row would violate the constraint, not create a dual member.
     *
     * The roster is what turns an incident's assignee (a username) into an address, so a
     * row that ends up with no address is refused rather than stored: it would look like
     * membership on this page while silently dropping that person from every notification.
     * The address is taken from the caller, else from the roster row this person already
     * has, else from auth.users — in that order, so moving a member between teams needs no
     * email typed and an admin who does type one is always the one who wins.
     *
     * No audit call here on purpose — teams.trg_team_employees_audit already records every
     * insert, update and delete on this table with the old and new row.
     */
    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        if (username.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A username is required."));
        }

        Team team = teamRepository.findById(id).orElse(null);
        if (team == null) return ResponseEntity.status(404).body(Map.of("error", "Team not found."));

        TeamEmployee member = memberRepository.findByUsername(username).orElse(null);

        String fullName = body.getOrDefault("fullName", "").trim();
        String role = body.getOrDefault("role", "").trim();
        String department = body.getOrDefault("department", "").trim();

        String email = body.getOrDefault("email", "").trim();
        if (email.isEmpty() && member != null && member.getEmail() != null) email = member.getEmail().trim();
        if (email.isEmpty()) email = userRepository.findByUsername(username).map(AppUser::getEmail).orElse("");
        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "No login named '" + username + "' and no email given. A member with no address can never be notified."));
        }
        if (!EMAIL.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a valid email address."));
        }

        if (fullName.isEmpty() && member != null && member.getFullName() != null) fullName = member.getFullName();
        if (fullName.isEmpty()) fullName = userRepository.findByUsername(username).map(AppUser::getFullName).orElse(username);

        if (role.isEmpty() && member != null && member.getRole() != null) role = member.getRole();
        if (department.isEmpty() && member != null && member.getDepartment() != null) department = member.getDepartment();

        String movedFrom = member == null || team.getId().equals(member.getTeam().getId())
                ? "" : member.getTeam().getName();
        if (member == null) member = new TeamEmployee(UUID.randomUUID(), username, fullName, email, role, department, team);
        member.setTeam(team);
        member.setEmail(email);
        member.setFullName(fullName);
        member.setRole(role);
        member.setDepartment(department);
        memberRepository.save(member);

        // Also update auth.users profile if user exists and changed
        final String finalFullName = fullName;
        final String finalEmail = email;
        final String finalDept = department;
        userRepository.findByUsername(username).ifPresent(u -> {
            boolean changed = false;
            if (finalFullName != null && !finalFullName.isBlank()) { u.setFullName(finalFullName); changed = true; }
            if (finalEmail != null && !finalEmail.isBlank()) { u.setEmail(finalEmail); changed = true; }
            if (finalDept != null && !finalDept.isBlank()) { u.setDepartment(finalDept); changed = true; }
            if (changed) userRepository.save(u);
        });

        return ResponseEntity.ok(Map.of("username", username, "fullName", fullName, "email", email,
                "role", role, "department", department, "team", team.getName(), "movedFrom", movedFrom));
    }

    /**
     * Updates an existing team member's details (full name, email, role, department).
     */
    @PutMapping("/{id}/members/{username}")
    public ResponseEntity<?> updateMember(@PathVariable UUID id, @PathVariable String username, @RequestBody Map<String, String> body) {
        TeamEmployee member = memberRepository.findByUsername(username.trim()).orElse(null);
        if (member == null || !id.equals(member.getTeam().getId())) {
            return ResponseEntity.status(404).body(Map.of("error", "That user is not on this team."));
        }

        String email = body.getOrDefault("email", "").trim();
        if (!email.isEmpty()) {
            if (!EMAIL.matcher(email).matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Not a valid email address."));
            }
            member.setEmail(email);
        }

        if (body.containsKey("fullName")) member.setFullName(body.get("fullName").trim());
        if (body.containsKey("role")) member.setRole(body.get("role").trim());
        if (body.containsKey("department")) member.setDepartment(body.get("department").trim());

        memberRepository.save(member);

        // Sync with auth.users if exists
        userRepository.findByUsername(username.trim()).ifPresent(u -> {
            boolean changed = false;
            if (member.getFullName() != null) { u.setFullName(member.getFullName()); changed = true; }
            if (member.getEmail() != null) { u.setEmail(member.getEmail()); changed = true; }
            if (member.getDepartment() != null) { u.setDepartment(member.getDepartment()); changed = true; }
            if (changed) userRepository.save(u);
        });

        return ResponseEntity.ok(Map.of(
                "username", member.getUsername(),
                "fullName", member.getFullName() != null ? member.getFullName() : "",
                "email", member.getEmail() != null ? member.getEmail() : "",
                "role", member.getRole() != null ? member.getRole() : "",
                "department", member.getDepartment() != null ? member.getDepartment() : "",
                "team", member.getTeam().getName()
        ));
    }

    /**
     * Takes a person off a team's roster. The team id in the path is checked against the
     * row, so a stale page cannot remove somebody from a team they already left.
     *
     * This drops the address that resolved their assignee name, so open incidents assigned
     * to them fall back to auth.users — the incident is never edited here.
     */
    @DeleteMapping("/{id}/members/{username}")
    public ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable String username) {
        TeamEmployee member = memberRepository.findByUsername(username.trim()).orElse(null);
        if (member == null || !id.equals(member.getTeam().getId())) {
            return ResponseEntity.status(404).body(Map.of("error", "That user is not on this team."));
        }
        memberRepository.delete(member);
        return ResponseEntity.ok(Map.of("removed", member.getUsername(), "team", member.getTeam().getName()));
    }
}
