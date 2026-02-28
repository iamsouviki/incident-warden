package com.company.mcp.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Confidence Calibration Job — spec §6 "Confidence Scoring & Learning".
 *
 * Runs nightly at 02:00.  Recalculates the reliability scores on
 * incident_patterns based on the last 90 days of resolved incidents.
 *
 * Algorithm:
 *   reliabilityScore = truePositives / (truePositives + falsePositives)
 *
 * Where:
 *   truePositives  — incidents matched by this pattern that were AUTO_RESOLVED
 *   falsePositives — incidents matched by this pattern that were escalated
 *                    (ESCALATED or required HITL → MODIFIED/REJECTED)
 *
 * Patterns with fewer than {@code mcp.calibration.min-samples} (default: 5)
 * observations in the window are skipped to avoid noisy updates.
 *
 * The job also logs a calibration report suitable for ingestion by
 * Prometheus / Grafana dashboards.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfidenceCalibrationJob {

    private final JdbcTemplate jdbcTemplate;

    private static final int MIN_SAMPLES   = 5;
    private static final int LOOKBACK_DAYS = 90;

    /**
     * Nightly calibration at 02:00 local time.
     * Override cron via {@code mcp.calibration.cron}.
     */
    @Scheduled(cron = "${mcp.calibration.cron:0 0 2 * * *}")
    @Transactional
    public void calibrate() {
        log.info("ConfidenceCalibrationJob starting — lookback={}d minSamples={}",
                LOOKBACK_DAYS, MIN_SAMPLES);

        try {
            // 1. Aggregate pattern outcomes from audit trail + incidents
            List<Map<String, Object>> outcomes = jdbcTemplate.queryForList("""
                    SELECT
                        ip.id              AS pattern_id,
                        ip.name            AS pattern_name,
                        ip.tenant_id       AS tenant_id,
                        COUNT(*)           AS total_matches,
                        SUM(CASE WHEN i.final_decision = 'AUTO_RESOLVE'   THEN 1 ELSE 0 END) AS true_positives,
                        SUM(CASE WHEN i.final_decision IN (
                                'ESCALATE_TO_HUMAN','HITL_REQUIRED') THEN 1 ELSE 0 END)      AS false_positives
                    FROM incident_patterns ip
                    JOIN incidents i
                         ON  i.tenant_id         = ip.tenant_id
                         AND i.classified_category = ip.category
                         AND i.created_at        >= now() - INTERVAL '%d days'
                    WHERE ip.is_active = true
                    GROUP BY ip.id, ip.name, ip.tenant_id
                    HAVING COUNT(*) >= %d
                    """.formatted(LOOKBACK_DAYS, MIN_SAMPLES));

            if (outcomes.isEmpty()) {
                log.info("ConfidenceCalibrationJob: no patterns with sufficient data — nothing updated");
                return;
            }

            int updated = 0;
            for (Map<String, Object> row : outcomes) {
                long tp    = toLong(row.get("true_positives"));
                long fp    = toLong(row.get("false_positives"));
                long total = toLong(row.get("total_matches"));

                if (total == 0) continue;

                double newScore = total > 0 ? (double) tp / (tp + fp + 1) : 0.5;
                // Smooth with previous score (EMA α=0.3)
                newScore = Math.min(1.0, Math.max(0.0, newScore));

                int rows = jdbcTemplate.update("""
                        UPDATE incident_patterns
                        SET reliability_score = ROUND(
                            (reliability_score * 0.7 + ? * 0.3)::numeric, 4),
                            updated_at = now()
                        WHERE id = ?
                        """, newScore, row.get("pattern_id"));

                updated += rows;
                log.info("Calibration: pattern='{}' tp={} fp={} total={} newScore={}",
                        row.get("pattern_name"), tp, fp, total, String.format("%.4f", newScore));
            }

            log.info("ConfidenceCalibrationJob complete — updated {} pattern(s)", updated);

        } catch (Exception e) {
            log.error("ConfidenceCalibrationJob failed: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private static long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }
}
