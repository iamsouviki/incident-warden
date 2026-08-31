package com.company.mcp.controller;

import com.company.mcp.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final UserRepository userRepository;

    public TeamController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getTeams() {
        return ResponseEntity.ok(List.of(
            Map.of("id", UUID.randomUUID().toString(), "name", "IT Ops", "description", "Global IT Operations & SRE", "employees", List.of())
        ));
    }

    @PostMapping
    public ResponseEntity<?> createTeam(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of("message", "Team created", "name", body.getOrDefault("name", "Team")));
    }

    @PutMapping("/{id}/email")
    public ResponseEntity<?> setTeamEmail(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of("id", id.toString(), "email", body.getOrDefault("email", "")));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @DeleteMapping("/{teamId}/members/{username}")
    public ResponseEntity<?> removeMember(@PathVariable UUID teamId, @PathVariable String username) {
        return ResponseEntity.ok(Map.of("status", "removed"));
    }
}
