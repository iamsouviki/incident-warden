package com.company.mcp.dashboard;

import com.company.mcp.model.HitlRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.AuditEventRepository;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AnalyticsController — spec §11 "Observability & Analytics".
 *
 * Exposes historical analytics endpoints for BI / Grafana:
 *
 *   GET /api/analytics/incidents?tenantId=…          — incident breakdown by status/severity
 *   GET /api/analytics/hitl-decisions?tenantId=…     — HITL decision distribution
 *   GET /api/analytics/audit-events?tenantId=…       — last N audit events
 */
@Slf4j
@RestController("dashboardAnalyticsController")
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final IncidentRepository    incidentRepository;
    private final HitlRequestRepository hitlRequestRepository;
    private final AuditEventRepository  auditEventRepository;

    // -------------------------------------------------------------------------
    // Incident analytics
    // -------------------------------------------------------------------------

    /**
     * GET /api/analytics/incidents?tenantId=&lt;uuid&gt;
     *
     * Returns counts grouped by status and severity.
     */
    @GetMapping("/incidents")
    public ResponseEntity<?> incidentBreakdown(@RequestParam String tenantId) {
        try {
            UUID tid = UUID.fromString(tenantId);
            List<Incident> all = incidentRepository.findByTenantIdOrderByCreatedAtDesc(tid);

            Map<String, Long> byStatus = all.stream()
                    .collect(Collectors.groupingBy(Incident::getStatus, Collectors.counting()));

            Map<String, Long> bySeverity = all.stream()
                    .collect(Collectors.groupingBy(i -> i.getSeverity() != null ? i.getSeverity() : "UNKNOWN",
                            Collectors.counting()));

            Map<String, Long> byDecision = all.stream()
                    .filter(i -> i.getFinalDecision() != null)
                    .collect(Collectors.groupingBy(Incident::getFinalDecision, Collectors.counting()));

            Map<String, Object> result = Map.of(
                    "tenantId",   tenantId,
                    "total",      all.size(),
                    "byStatus",   byStatus,
                    "bySeverity", bySeverity,
                    "byDecision", byDecision
            );
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tenantId"));
        } catch (Exception e) {
            log.error("incidentBreakdown failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // HITL decision analytics
    // -------------------------------------------------------------------------

    /**
     * GET /api/analytics/hitl-decisions?tenantId=&lt;uuid&gt;
     *
     * Returns HITL decision breakdown, average decision time, and SLA compliance.
     */
    @GetMapping("/hitl-decisions")
    public ResponseEntity<?> hitlDecisions(@RequestParam String tenantId) {
        try {
            UUID tid = UUID.fromString(tenantId);
            List<HitlRequest> all = hitlRequestRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tid, "APPROVED");
            all.addAll(hitlRequestRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tid, "REJECTED"));
            all.addAll(hitlRequestRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tid, "ESCALATED"));
            all.addAll(hitlRequestRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tid, "EXPIRED"));

            Map<String, Long> byDecision = all.stream()
                    .filter(r -> r.getDecision() != null)
                    .collect(Collectors.groupingBy(HitlRequest::getDecision, Collectors.counting()));

            // Average resolution time (ms) for decided requests
            java.util.OptionalDouble avgMs = all.stream()
                    .filter(r -> r.getDecidedAt() != null && r.getCreatedAt() != null)
                    .mapToLong(r -> java.time.Duration.between(r.getCreatedAt(), r.getDecidedAt()).toMillis())
                    .average();

            long expiredCount = all.stream().filter(r -> "EXPIRED".equals(r.getStatus())).count();
            long totalDecided = all.size();
            double slaCompliance = totalDecided > 0
                    ? Math.round(((double)(totalDecided - expiredCount) / totalDecided) * 1000.0) / 10.0
                    : 100.0;

            Map<String, Object> result = new HashMap<>();
            result.put("tenantId",                    tenantId);
            result.put("total",                       totalDecided);
            result.put("byDecision",                  byDecision);
            result.put("avgDecisionTimeMs",           avgMs.isPresent() ? (long) avgMs.getAsDouble() : null);
            result.put("slaCompliancePct",            slaCompliance);
            result.put("expiredWithoutDecision",      expiredCount);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tenantId"));
        } catch (Exception e) {
            log.error("hitlDecisions failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Audit event analytics
    // -------------------------------------------------------------------------

    /**
     * GET /api/analytics/audit-events?tenantId=&lt;uuid&gt;[&amp;limit=50]
     *
     * Returns the latest audit events grouped by event type.
     */
    @GetMapping("/audit-events")
    public ResponseEntity<?> auditEvents(
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            UUID tid = UUID.fromString(tenantId);
            var events = auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tid);
            var limited = events.stream().limit(limit).toList();

            Map<String, Long> byType = limited.stream()
                    .collect(Collectors.groupingBy(e -> e.getEventType() != null ? e.getEventType() : "UNKNOWN",
                            Collectors.counting()));

            Map<String, Object> result = Map.of(
                    "tenantId",  tenantId,
                    "total",     limited.size(),
                    "byType",    byType,
                    "events",    limited
            );
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tenantId"));
        } catch (Exception e) {
            log.error("auditEvents failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

}
