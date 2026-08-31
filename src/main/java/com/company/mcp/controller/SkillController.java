package com.company.mcp.controller;

import com.company.mcp.model.Skill;
import com.company.mcp.service.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin surface for what the agent recognises: categorisation, extraction, execution.
 *
 * GET is open to anyone signed in, because an analyst reading a plan is owed the definition
 * of the tool it names. Writes are ADMIN-only — enforced in {@code SecurityConfig} for the
 * route and again in {@link SkillService} for the {@code mutating} field specifically, since
 * that field is the only one that changes what may run rather than what may be recognised.
 *
 * One POST for create and update: the natural key is (tenant, kind, key), so re-posting a
 * key edits that row. A separate PUT would only add a way for the two to disagree.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillService skills;

    public SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    public List<Skill> list() {
        return skills.all();
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Skill skill) {
        try {
            return ResponseEntity.ok(skills.save(skill));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // The message is written for the admin who typed the bad pattern, so it is
            // returned rather than swallowed into a generic 400.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        try {
            skills.delete(id);
            return ResponseEntity.ok(Map.of("status", "DELETED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
