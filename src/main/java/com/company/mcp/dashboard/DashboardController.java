package com.company.mcp.dashboard;

import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.util.ApiErrorResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DashboardController — spec §11 "Observability & Dashboards".
 *
 * Provides real-time KPI metrics for the ops dashboard:
 *   GET /api/dashboard/kpis?tenantId=…  — headline metrics
 *
 * KPIs returned:
 *   autoResolveRate      — % of total incidents resolved automatically
 *   hitlPending          — current backlog of pending HITL approvals
 *   mttrAutoMinutes      — mean time to resolve (auto) in minutes (stub)
 *   falsePositiveRate    — % of auto-resolved later escalated (stub)
 *   totalIncidents       — total registered incidents
 *   autoResolved         — count of AUTO_RESOLVED incidents
 *   escalated            — count of ESCALATED incidents
 *   p1OpenCount          — number of open P1 incidents
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final IncidentRepository    incidentRepository;
    private final HitlRequestRepository hitlRequestRepository;

    /**
     * GET /api/dashboard/kpis?tenantId=&lt;uuid&gt;
     */
    @GetMapping("/kpis")
    public ResponseEntity<?> getKpis(@RequestParam String tenantId) {
        try {
            UUID tid = UUID.fromString(tenantId);

            long total       = incidentRepository.countByTenantIdAndStatus(tid, "AUTO_RESOLVED")
                             + incidentRepository.countByTenantIdAndStatus(tid, "ESCALATED")
                             + incidentRepository.countByTenantIdAndStatus(tid, "HITL_PENDING")
                             + incidentRepository.countByTenantIdAndStatus(tid, "PENDING")
                             + incidentRepository.countByTenantIdAndStatus(tid, "PROCESSING");

            long autoResolved = safeCount(incidentRepository.countByTenantIdAndStatus(tid, "AUTO_RESOLVED"));
            long escalated    = safeCount(incidentRepository.countByTenantIdAndStatus(tid, "ESCALATED"));
            long hitlPending  = safeCount(hitlRequestRepository.countByTenantIdAndStatus(tid, "PENDING"));
            long p1Open       = safeCount(incidentRepository.countByTenantIdAndStatusAndSeverity(tid, "PROCESSING", "P1"))
                              + safeCount(incidentRepository.countByTenantIdAndStatusAndSeverity(tid, "PENDING",    "P1"))
                              + safeCount(incidentRepository.countByTenantIdAndStatusAndSeverity(tid, "HITL_PENDING","P1"));

            double autoResolveRate = total > 0 ? Math.round((double) autoResolved / total * 1000.0) / 10.0 : 0.0;

            Map<String, Object> kpis = new HashMap<>();
            kpis.put("tenantId",         tenantId);
            kpis.put("totalIncidents",   total);
            kpis.put("autoResolved",     autoResolved);
            kpis.put("escalated",        escalated);
            kpis.put("hitlPending",      hitlPending);
            kpis.put("p1OpenCount",      p1Open);
            kpis.put("autoResolveRate",  autoResolveRate);   // percentage
            kpis.put("mttrAutoMinutes",  null);              // TODO: compute from resolvedAt - createdAt
            kpis.put("falsePositiveRate", null);             // TODO: track re-escalations

            return ResponseEntity.ok(kpis);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tenantId format"));
        } catch (Exception e) {
            log.error("Dashboard KPI fetch failed: {}", e.getMessage(), e);
            return ApiErrorResponses.internalServerError();
        }
    }

    // -------------------------------------------------------------------------

    private static long safeCount(Integer count) {
        return count == null ? 0L : count.longValue();
    }
}
