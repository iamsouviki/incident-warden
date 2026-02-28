package com.company.mcp.scheduler;

import com.company.mcp.model.Incident;
import com.company.mcp.service.IncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Incident Processing Scheduler - Phase 9 Implementation.
 * Polls for PENDING incidents and processes them through the agent pipeline.
 * 
 * Scheduling strategy:
 * - Polls every 10 seconds for PENDING incidents
 * - Claims batch of 5 incidents at a time (SKIP LOCKED)
 * - Processes them through agent pipeline
 * - Handles failures with retry logic
 * - Scales with multiple instances (safe via SKIP LOCKED)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentProcessingScheduler {
    private final IncidentService incidentService;

    private static final long PROCESSING_INTERVAL_MS = 10000; // 10 seconds
    private static final int BATCH_SIZE = 5;

    /**
     * Process batch of incidents every 10 seconds.
     * Runs in background thread managed by Spring @Scheduled.
     */
    @Scheduled(fixedRate = PROCESSING_INTERVAL_MS)
    public void processIncidentBatch() {
        try {
            log.debug("Starting incident batch processing");

            // Process a batch globally (all tenants)
            // In multi-tenant scenario, could iterate per tenant
            int processed = incidentService.processBatch(null);

            if (processed > 0) {
                log.info("Processed {} incidents in batch", processed);
            }

        } catch (Exception e) {
            log.error("Incident batch processing failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup stale incidents every minute.
     * Marks incidents as escalated if they're stuck in PROCESSING too long.
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void cleanupStaleIncidents() {
        try {
            log.debug("Checking for stale incidents");

            // In Phase 9+, would query incidents in PROCESSING state
            // for > 10 minutes and escalate them
            // For now, logged for future implementation

        } catch (Exception e) {
            log.error("Stale incident cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup expired HITL requests every 30 seconds.
     * Escalates to senior analyst if HITL approval expires.
     */
    @Scheduled(fixedRate = 30000) // 30 seconds
    public void cleanupExpiredHitlRequests() {
        try {
            log.debug("Checking for expired HITL requests");

            // In Phase 8+, would query expired HITL requests
            // and escalate them
            // For now, logged for future implementation

        } catch (Exception e) {
            log.error("HITL cleanup failed: {}", e.getMessage(), e);
        }
    }
}
