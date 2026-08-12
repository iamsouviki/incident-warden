package com.company.mcp.controller;

import com.company.mcp.dto.NormalizedIncidentRequest;
import com.company.mcp.service.IncidentIntakeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/intake/incidents")
public class IncidentIntakeController {
    private final IncidentIntakeService intake;
    public IncidentIntakeController(IncidentIntakeService intake) { this.intake = intake; }

    @PostMapping
    public ResponseEntity<?> ingest(@RequestBody NormalizedIncidentRequest request) {
        try { return ResponseEntity.ok(intake.ingest(request)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage())); }
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<?> importFile(@RequestPart("file") MultipartFile file,
                                        @RequestParam(value = "sourceSystem", defaultValue = "Custom Import") String sourceSystem) {
        try { return ResponseEntity.ok(intake.importFile(file, sourceSystem)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage())); }
    }
}
