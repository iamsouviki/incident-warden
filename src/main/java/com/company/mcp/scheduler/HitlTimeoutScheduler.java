package com.company.mcp.scheduler;

import com.company.mcp.model.HitlRequest;
import com.company.mcp.repository.HitlRequestRepository;
import com.company.mcp.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * HITL Timeout Scheduler — spec §7 "Human-in-the-Loop Escalation".
 *
 * Runs every 60 s.  SLA response windows per severity:
 *
 *   P1 →  15 minutes
 *   P2 →  30 minutes
 *   P3 → 120 minutes
 *   P4 → 480 minutes
 *
 * When a HITL request expires the incident is:
 *   1. Moved to {@code ESCALATED} with finalDecision {@code ESCALATE_TO_HUMAN}.
 *   2. The HITL request itself is marked {@code EXPIRED}.
 *   3. An SLA-breach warning is logged (ready for Prometheus counter increment).
 *
 * NOTE: Real-world implementation should also fire a Slack / PagerDuty alert
 * via {@code HitlNotificationService} (wired in a future step).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitlTimeoutScheduler {

    private final HitlRequestRepository hitlRequestRepository;
    private final IncidentRepository    incidentRepository;

    /**
     * Check for expired HITL requests every 60 s.
     */
    @Scheduled(fixedRateString = "${mcp.hitl.timeout-check-interval-ms:60000}")
    @Transactional
    public void expireTimedOutRequests() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<HitlRequest> expired = hitlRequestRepository.findByExpiresAtBefore(now)
                    .stream()
                    .filter(r -> "PENDING".equals(r.getStatus()))
                    .toList();

            if (expired.isEmpty()) {
                log.debug("HitlTimeoutScheduler: no expired HITL requests");
                return;
            }

            log.warn("HitlTimeoutScheduler: {} HITL request(s) have expired — escalating", expired.size());

            for (HitlRequest req : expired) {
                try {
                    escalate(req, now);
                } catch (Exception e) {
                    log.error("Failed to escalate HITL request {}: {}", req.getId(), e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("HitlTimeoutScheduler sweep failed: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void escalate(HitlRequest req, LocalDateTime now) {
        // 1. Expire the HITL request
        req.setStatus("EXPIRED");
        req.setDecisionReason("Auto-escalated by timeout scheduler at " + now);
        hitlRequestRepository.save(req);

        // 2. Update the parent incident
        incidentRepository.findById(req.getIncidentId()).ifPresentOrElse(incident -> {
            String prevStatus = incident.getStatus();
            incident.setStatus("ESCALATED");
            incident.setFinalDecision("ESCALATE_TO_HUMAN");
            incidentRepository.save(incident);

            log.warn("SLA_BREACH: incident={} severity={} hitlRequest={} prevStatus={} "
                    + "expiredAt={} — escalated to senior analyst",
                    incident.getId(), incident.getSeverity(),
                    req.getId(), prevStatus, req.getExpiresAt());

            // TODO: fire HitlNotificationService.triggerPagerDuty(incident, "SLA_BREACH")

        }, () -> log.error("HitlTimeout: no incident found for hitlRequest={}", req.getId()));
    }
}
