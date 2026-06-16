package com.company.mcp.controller;

import com.company.mcp.model.IncidentStatus;
import com.company.mcp.repository.IncidentStatusRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statuses")
public class StatusController {

    @Autowired
    private IncidentStatusRepository statusRepository;

    @GetMapping
    public ResponseEntity<List<IncidentStatus>> getStatuses() {
        return ResponseEntity.ok(statusRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<IncidentStatus> createStatus(@RequestBody IncidentStatus status) {
        if (status.getId() == null) {
            status.setId(UUID.randomUUID());
        }
        IncidentStatus saved = statusRepository.save(status);
        return ResponseEntity.ok(saved);
    }
}
