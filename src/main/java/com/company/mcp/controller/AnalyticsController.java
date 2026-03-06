package com.company.mcp.controller;

import com.company.mcp.repository.AuditEventRepository;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.service.IncidentService;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AnalyticsController — aggregated metrics for the dashboard.
 *
 * GET /api/v1/analytics/overview/{tenantId}   → combined incident + HITL counters
 */
@Slf4j
@RestController("v1AnalyticsController")
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final IncidentService incidentService;
    private final HitlRequestRepository hitlRepo;
    private final AuditEventRepository auditRepo;

    /**
     * Returns combined stats for the overview dashboard cards.
     */
    @GetMapping("/overview/{tenantId}")
    public ResponseEntity<?> overview(@PathVariable String tenantId) {
        try {
            Map<String, Object> incidents = new LinkedHashMap<>();
            incidents.put("totalPending",  incidentService.countByTenantAndStatus(tenantId, "PENDING"));
            incidents.put("processing",    incidentService.countByTenantAndStatus(tenantId, "PROCESSING"));
            incidents.put("autoResolved",  incidentService.countAutoResolved(tenantId));
            incidents.put("hitlPending",   incidentService.countByTenantAndStatus(tenantId, "HITL_PENDING"));
            incidents.put("escalated",     incidentService.countByTenantAndStatus(tenantId, "ESCALATED"));
            incidents.put("resolved",      incidentService.countByTenantAndStatus(tenantId, "RESOLVED"));
            incidents.put("failed",        incidentService.countByTenantAndStatus(tenantId, "FAILED"));

            Integer pending   = hitlRepo.countByStatus("PENDING");
            Integer approved  = hitlRepo.countByStatus("APPROVED");
            Integer modified  = hitlRepo.countByStatus("MODIFIED");
            Integer rejected  = hitlRepo.countByStatus("REJECTED");
            Integer escalated = hitlRepo.countByStatus("ESCALATED");

            Map<String, Object> hitl = new LinkedHashMap<>();
            hitl.put("pending",   pending   != null ? pending   : 0);
            hitl.put("approved",  approved  != null ? approved  : 0);
            hitl.put("modified",  modified  != null ? modified  : 0);
            hitl.put("rejected",  rejected  != null ? rejected  : 0);
            hitl.put("escalated", escalated != null ? escalated : 0);

            long auditCount = auditRepo.count();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("incidents", incidents);
            result.put("hitl",      hitl);
            result.put("auditEventCount", auditCount);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Analytics overview failed for tenant {}", tenantId, e);
            return ApiErrorResponses.internalServerError();
        }
    }
}
