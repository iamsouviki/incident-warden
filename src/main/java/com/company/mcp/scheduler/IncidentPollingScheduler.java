package com.company.mcp.scheduler;

import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incident Polling Scheduler — spec §4 "External Source Integration".
 *
 * Polls external ticketing / monitoring systems every 60 s and creates
 * Incident records for any new events.  Each source maintains a high-water
 * mark (last-seen timestamp) so only new events are ingested.
 *
 * Supported sources (stubbed, wire your real clients here):
 *   • ServiceNow  — REST Table API
 *   • Freshservice — v2 Tickets API
 *   • Prometheus  — Alertmanager /api/v2/alerts
 *   • PagerDuty   — Events v2
 *
 * Deduplication is guaranteed by the UNIQUE constraint on
 * (source_system, source_ticket_id) in the incidents table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentPollingScheduler {

    private final IncidentRepository incidentRepository;

    @Value("${mcp.polling.enabled:true}")
    private boolean pollingEnabled;

    @Value("${mcp.polling.default-tenant-id:00000000-0000-0000-0000-000000000001}")
    private String defaultTenantId;

    /** In-memory high-water marks per source.  Replace with DB-backed state for HA. */
    private final Map<String, Instant> watermarks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Scheduled entry point
    // -------------------------------------------------------------------------

    /**
     * Main polling loop.  Runs every 60 s (configurable via
     * {@code mcp.polling.interval-ms}).
     */
    @Scheduled(fixedDelayString = "${mcp.polling.interval-ms:60000}")
    public void pollAllSources() {
        if (!pollingEnabled) {
            log.debug("Incident polling disabled — skipping");
            return;
        }

        log.debug("Starting external source polling cycle");

        int total = 0;
        total += pollServiceNow();
        total += pollFreshservice();
        total += pollPrometheus();
        total += pollPagerDuty();

        if (total > 0) {
            log.info("Polling cycle complete — ingested {} new incidents", total);
        } else {
            log.debug("Polling cycle complete — no new incidents");
        }
    }

    // -------------------------------------------------------------------------
    // Per-source pollers (replace stub bodies with real HTTP clients)
    // -------------------------------------------------------------------------

    private int pollServiceNow() {
        String source = "ServiceNow";
        Instant since = watermarks.getOrDefault(source, Instant.now().minusSeconds(300));
        try {
            // TODO: inject ServiceNow REST client and fetch incidents updated since `since`
            // List<ServiceNowTicket> tickets = serviceNowClient.getUpdatedIncidents(since);
            // return ingest(source, tickets.stream().map(this::toIncident).toList());
            log.trace("ServiceNow polling stub — watermark={}", since);
            watermarks.put(source, Instant.now());
            return 0;
        } catch (Exception e) {
            log.error("ServiceNow polling error: {}", e.getMessage(), e);
            return 0;
        }
    }

    private int pollFreshservice() {
        String source = "Freshservice";
        Instant since = watermarks.getOrDefault(source, Instant.now().minusSeconds(300));
        try {
            // TODO: inject Freshservice v2 client
            log.trace("Freshservice polling stub — watermark={}", since);
            watermarks.put(source, Instant.now());
            return 0;
        } catch (Exception e) {
            log.error("Freshservice polling error: {}", e.getMessage(), e);
            return 0;
        }
    }

    private int pollPrometheus() {
        String source = "Prometheus";
        Instant since = watermarks.getOrDefault(source, Instant.now().minusSeconds(300));
        try {
            // TODO: inject Alertmanager client, translate alert → Incident
            log.trace("Prometheus polling stub — watermark={}", since);
            watermarks.put(source, Instant.now());
            return 0;
        } catch (Exception e) {
            log.error("Prometheus polling error: {}", e.getMessage(), e);
            return 0;
        }
    }

    private int pollPagerDuty() {
        String source = "PagerDuty";
        Instant since = watermarks.getOrDefault(source, Instant.now().minusSeconds(300));
        try {
            // TODO: inject PagerDuty Events v2 client
            log.trace("PagerDuty polling stub — watermark={}", since);
            watermarks.put(source, Instant.now());
            return 0;
        } catch (Exception e) {
            log.error("PagerDuty polling error: {}", e.getMessage(), e);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Ingestion helper
    // -------------------------------------------------------------------------

    /**
     * Persist incidents, silently swallowing duplicates (UNIQUE constraint).
     */
    @Transactional
    public int ingest(String sourceSystem, java.util.List<Incident> candidates) {
        int created = 0;
        for (Incident candidate : candidates) {
            boolean exists = incidentRepository
                    .findBySourceSystemAndSourceTicketId(sourceSystem, candidate.getSourceTicketId())
                    .isPresent();
            if (!exists) {
                candidate.setTenantId(UUID.fromString(defaultTenantId));
                candidate.setSourceSystem(sourceSystem);
                candidate.setStatus("PENDING");
                incidentRepository.save(candidate);
                created++;
                log.info("Ingested new incident from {}: ticketId={}", sourceSystem,
                        candidate.getSourceTicketId());
            }
        }
        return created;
    }
}
