package com.company.mcp.controller;

import com.company.mcp.service.RemediationTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/remediation-templates")
@RequiredArgsConstructor
public class RemediationTemplateController {

    private final RemediationTemplateService remediationTemplateService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String tenantId) {
        UUID tid = UUID.fromString(tenantId);
        var templates = remediationTemplateService.list(tid);
        return ResponseEntity.ok(Map.of("count", templates.size(), "templates", templates));
    }
}
