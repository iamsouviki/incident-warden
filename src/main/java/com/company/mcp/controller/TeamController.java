package com.company.mcp.controller;

import com.company.mcp.model.AppUser;
import com.company.mcp.model.Team;
import com.company.mcp.model.TeamEmployee;
import com.company.mcp.repository.TeamEmployeeRepository;
import com.company.mcp.repository.TeamRepository;
import com.company.mcp.repository.UserRepository;
import com.company.mcp.service.NotificationService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

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
        return ResponseEntity.ok(teamRepository.findAll(Sort.by("name")));
    }

    @PostMapping
    public ResponseEntity<?> createTeam(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        String description = body.getOrDefault("description", "").trim();
        String email = body.getOrDefault("email", "").trim();

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team name is required."));
        }
        if (!email.isEmpty() && !NotificationService.isSendableAddress(email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a valid email address."));
        }

        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setName(name);
        team.setDescription(description);
        team.setEmail(email.isEmpty() ? null : email);
        team.setEmployees(new ArrayList<>());

        Team saved = teamRepository.save(team);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}/email")
    public ResponseEntity<?> setTeamEmail(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim();
        if (!email.isEmpty() && !NotificationService.isSendableAddress(email)) {
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
        // Three sources, most authoritative first: what the admin typed, the member's own
        // roster row (a seeded member may have no login at all), then auth.users.
        if (email.isEmpty() && member != null && member.getEmail() != null) email = member.getEmail().trim();
        if (email.isEmpty()) email = userRepository.findByUsername(username).map(AppUser::getEmail).orElse("");
        // Refuse rather than invent. This roster is what turns an incident's assignee into an
        // address, so a row with a made-up "username@company.local" is a member every
        // notification silently fails to reach — worse than no row, because the UI shows it
        // as covered. Ask for the address instead: the caller can supply one in this request.
        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "No account or address exists for '" + username + "', so this member can never be notified. "
                            + "Check the username, or supply an email address with it."));
        }
        if (!NotificationService.isSendableAddress(email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a valid email address: " + email));
        }

        String movedFrom = null;
        if (member == null) {
            member = new TeamEmployee();
            member.setId(UUID.randomUUID());
            member.setUsername(username);
        } else if (!id.equals(member.getTeam().getId())) {
            movedFrom = member.getTeam().getName();
        }

        member.setTeam(team);
        member.setEmail(email);
        if (!fullName.isEmpty()) member.setFullName(fullName);
        if (!role.isEmpty()) member.setRole(role);
        if (!department.isEmpty()) member.setDepartment(department);

        memberRepository.save(member);

        // Sync with auth.users if exists
        TeamEmployee finalMember = member;
        userRepository.findByUsername(username).ifPresent(u -> {
            boolean changed = false;
            if (finalMember.getFullName() != null) { u.setFullName(finalMember.getFullName()); changed = true; }
            if (finalMember.getEmail() != null) { u.setEmail(finalMember.getEmail()); changed = true; }
            if (finalMember.getDepartment() != null) { u.setDepartment(finalMember.getDepartment()); changed = true; }
            if (changed) userRepository.save(u);
        });

        return ResponseEntity.ok(Map.of(
                "username", member.getUsername(),
                "fullName", member.getFullName() != null ? member.getFullName() : "",
                "email", member.getEmail(),
                "role", member.getRole() != null ? member.getRole() : "",
                "department", member.getDepartment() != null ? member.getDepartment() : "",
                "team", team.getName(),
                "movedFrom", movedFrom != null ? movedFrom : ""
        ));
    }

    @PutMapping("/{id}/members/{username}")
    public ResponseEntity<?> updateMember(@PathVariable UUID id, @PathVariable String username,
                                          @RequestBody Map<String, String> body) {
        TeamEmployee member = memberRepository.findByUsername(username.trim()).orElse(null);
        if (member == null || !id.equals(member.getTeam().getId())) {
            return ResponseEntity.status(404).body(Map.of("error", "That user is not on this team."));
        }

        String email = body.getOrDefault("email", "").trim();
        if (!email.isEmpty()) {
            if (!NotificationService.isSendableAddress(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Not a valid email address."));
            }
            member.setEmail(email);
        }

        if (body.containsKey("fullName")) member.setFullName(body.get("fullName").trim());
        if (body.containsKey("role")) member.setRole(body.get("role").trim());
        if (body.containsKey("department")) member.setDepartment(body.get("department").trim());

        memberRepository.save(member);

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
