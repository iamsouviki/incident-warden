package com.company.mcp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stale Job Recovery Scheduler — spec §9 "Reliability & Recovery".
 *
 * Runs every 5 minutes.  Finds incidents that have been stuck in
 * {@code PROCESSING} state for more than {@code mcp.stale.threshold-minutes}
 * (default 10 min) and resets them back to {@code PENDING} so the
 * {@link IncidentProcessingScheduler} can pick them up again.
 *
 * This handles the case where a node crashed mid-pipeline without completing
 * the agent chain.  The SKIP LOCKED pattern in claimNextBatch() ensures
 * another node picks up the reset job without a race condition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobRecoveryScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Value("${mcp.stale.threshold-minutes:10}")
    private int staleThresholdMinutes;

    @Value("${mcp.stale.max-retries:3}")
    private int maxRetries;

    /**
     * Recovery sweep — every 5 minutes.
     */
    @Scheduled(fixedRateString = "${mcp.stale.sweep-interval-ms:300000}")
    @Transactional
    public void recoverStaleJobs() {
        try {
            // Reset incidents stuck in PROCESSING beyond threshold, up to maxRetries times.
            // Beyond maxRetries we escalate rather than loop endlessly.
            int reset = jdbcTemplate.update("""
                    UPDATE incidents
                    SET status             = 'PENDING',
                        processing_started_at = NULL,
                        retry_count        = COALESCE(retry_count, 0) + 1,
                        updated_at         = now()
                    WHERE status = 'PROCESSING'
                      AND processing_started_at < now() - INTERVAL '%d minutes'
                      AND COALESCE(retry_count, 0) < %d
                    """.formatted(staleThresholdMinutes, maxRetries));

            if (reset > 0) {
                log.warn("StaleJobRecovery: reset {} stale PROCESSING incident(s) back to PENDING "
                        + "(threshold={}m, maxRetries={})", reset, staleThresholdMinutes, maxRetries);
            } else {
                log.debug("StaleJobRecovery: no stale incidents found");
            }

            // Incidents that have exhausted retries are escalated.
            int escalated = jdbcTemplate.update("""
                    UPDATE incidents
                    SET status         = 'ESCALATED',
                        final_decision = 'ESCALATE_TO_HUMAN',
                        updated_at     = now()
                    WHERE status = 'PROCESSING'
                      AND processing_started_at < now() - INTERVAL '%d minutes'
                      AND COALESCE(retry_count, 0) >= %d
                    """.formatted(staleThresholdMinutes, maxRetries));

            if (escalated > 0) {
                log.error("StaleJobRecovery: escalated {} incident(s) that exhausted {} retries — "
                        + "manual review required", escalated, maxRetries);
            }

        } catch (Exception e) {
            log.error("StaleJobRecovery sweep failed: {}", e.getMessage(), e);
        }
    }
}
