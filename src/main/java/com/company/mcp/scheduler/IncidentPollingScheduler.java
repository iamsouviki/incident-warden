package com.company.mcp.scheduler;

import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import com.company.mcp.service.integration.FreshServiceClient;
import com.company.mcp.service.integration.ServiceNowClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incident Polling Scheduler — spec §4 "External Source Integration".
 *
 * Polls external ITSM ticketing systems every 60 s (configurable) and creates
 * Incident records for any new events.  Each source maintains a high-water
 * mark (last-seen timestamp) so only new events are ingested.
 *
 * Supported sources:
 *   • ServiceNow   — REST Table API  (/api/now/table/incident)
 *   • FreshService  — Tickets API v2  (/api/v2/tickets)
 *
 * Alert flow:
 *   ServiceNow / FreshService  ──(poll every 60s)──▶  incidents table (PENDING)
 *                                                       │
 *   IncidentProcessingScheduler ──(every 10s)────────▶  claim PENDING batch
 *                                                       │
 *   AgentPipeline.processIncident() ──────────────────▶  9-agent pipeline
 *
 * Deduplication is guaranteed by the UNIQUE constraint on
 * (source_system, source_ticket_id) in the incidents table.
 */
@Slf4j
@Component
public class IncidentPollingScheduler {

    private final IncidentRepository incidentRepository;

    /** Optional — only injected when mcp.servicenow.enabled=true */
    @Autowired(required = false)
    private ServiceNowClient serviceNowClient;

    /** Optional — only injected when mcp.freshservice.enabled=true */
    @Autowired(required = false)
    private FreshServiceClient freshServiceClient;

    @Value("${mcp.polling.enabled:true}")
    private boolean pollingEnabled;

    @Value("${mcp.polling.default-tenant-id:00000000-0000-0000-0000-000000000001}")
    private String defaultTenantId;

    /** In-memory high-water marks per source.  Replace with DB-backed state for HA. */
    private final Map<String, Instant> watermarks = new ConcurrentHashMap<>();

    public IncidentPollingScheduler(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    // -------------------------------------------------------------------------
    // Scheduled entry point
    // -------------------------------------------------------------------------

    /**
     * Main polling loop.  Runs every 60 s (configurable via
     * {@code mcp.polling.interval-ms}).
     *
     * Only polls sources that are enabled via configuration:
     *   - mcp.servicenow.enabled=true  → polls ServiceNow
     *   - mcp.freshservice.enabled=true → polls FreshService
     */
    @Scheduled(fixedDelayString = "${mcp.polling.interval-ms:60000}")
    public void pollAllSources() {
        if (!pollingEnabled) {
            log.debug("Incident polling disabled — skipping");
            return;
        }

        log.info("┌─── Polling Cycle Started ──────────────────────────────────────┐");

        int total = 0;
        total += pollServiceNow();
        total += pollFreshservice();

        if (total > 0) {
            log.info("└─── Polling Cycle Complete — ingested {} new incident(s) ───────┘", total);
        } else {
            log.info("└─── Polling Cycle Complete — no new incidents ───────────────────┘");
        }
    }

    // -------------------------------------------------------------------------
    // Per-source pollers
    // -------------------------------------------------------------------------

    /**
     * Poll ServiceNow Table API for new/updated incidents.
     * Only active when {@code mcp.servicenow.enabled=true}.
     */
    private int pollServiceNow() {
        if (serviceNowClient == null) {
            log.trace("ServiceNow client not configured — skipping");
            return 0;
        }

        String source = "ServiceNow";
        Instant since = watermarks.getOrDefault(source, Instant.now().minusSeconds(300));
        try {
            log.info("[ServiceNow] Polling for incidents updated since {}", since);

            List<Incident> tickets = serviceNowClient.getUpdatedIncidents(since);

            int ingested = ingest(source, tickets);

            watermarks.put(source, Instant.now());
            log.info("[ServiceNow] Poll complete — fetched={}, ingested={}", tickets.size(), ingested);
            return ingested;
        } catch (Exception e) {
            log.error("[ServiceNow] Polling error: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Poll FreshService Tickets API v2 for new/updated tickets.
     * Only active when {@code mcp.freshservice.enabled=true}.
     */
    private int pollFreshservice() {
        if (freshServiceClient == null) {
            log.trace("FreshService client not configured — skipping");
            return 0;
        }

        String source = "FreshService";
        Instant since = watermarks.getOrDefault(source, Instant.now().minusSeconds(300));
        try {
            log.info("[FreshService] Polling for tickets updated since {}", since);

            List<Incident> tickets = freshServiceClient.getUpdatedTickets(since);

            int ingested = ingest(source, tickets);

            watermarks.put(source, Instant.now());
            log.info("[FreshService] Poll complete — fetched={}, ingested={}", tickets.size(), ingested);
            return ingested;
        } catch (Exception e) {
            log.error("[FreshService] Polling error: {}", e.getMessage(), e);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Ingestion helper
    // -------------------------------------------------------------------------

    /**
     * Persist incidents, silently skipping duplicates (UNIQUE constraint on
     * source_system + source_ticket_id).
     */
    @Transactional
    public int ingest(String sourceSystem, List<Incident> candidates) {
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
                log.info("Ingested new incident from {}: ticketId={}, title='{}'",
                        sourceSystem, candidate.getSourceTicketId(), candidate.getTitle());
            } else {
                log.debug("Duplicate skipped from {}: ticketId={}", sourceSystem, candidate.getSourceTicketId());
            }
        }
        return created;
    }
}
