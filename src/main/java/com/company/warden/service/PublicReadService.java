package com.company.warden.service;

import com.company.warden.model.SystemConfig;
import com.company.warden.repository.IncidentRepository;
import com.company.warden.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What someone with no account is allowed to know.
 *
 * The requirement is that a stranger can see how many incidents exist and what state they are
 * in, and is asked to sign in only when they want something <em>fixed</em>. Two decisions
 * follow from that and are the whole point of this class:
 *
 * <ol>
 *   <li><b>No model call.</b> Anonymous answers come from SQL. An unauthenticated LLM route is
 *       a provider-budget DoS with a prompt-injection surface and nobody to attribute it to;
 *       and {@link RagService#askStrictSopRag} needs an authenticated identity, which an
 *       anonymous caller does not have.</li>
 *   <li><b>Redaction is the projection.</b> {@link Row} carries five fields, and the query
 *       selects those five columns — descriptions, assignees, teams, notes and target hosts
 *       never leave the database on this path. Filtering a full entity afterwards is the
 *       version of this that leaks the first time someone adds a field.</li>
 * </ol>
 */
@Service
public class PublicReadService {
    private static final Logger log = LoggerFactory.getLogger(PublicReadService.class);

    /** UI-managed switch, on by default: an anonymous read is the front door of the product. */
    public static final String ENABLED_KEY = "public_read_enabled";

    /** Enough to answer a question, not enough to page through the ticket table. */
    private static final int MAX_ROWS = 20;

    /** Anything else counts as open — an unrecognised status is not quietly treated as done. */
    private static final Set<String> CLOSED_STATES = Set.of("resolved", "closed", "cancelled");

    private final SystemConfigRepository config;
    private final IncidentRepository incidents;

    public PublicReadService(SystemConfigRepository config, IncidentRepository incidents) {
        this.config = config;
        this.incidents = incidents;
    }

    public boolean enabled() {
        return config.findById(ENABLED_KEY).map(SystemConfig::getConfigValue).map(Boolean::parseBoolean).orElse(true);
    }

    public void setEnabled(boolean value) {
        config.save(new SystemConfig(ENABLED_KEY, Boolean.toString(value)));
        log.warn("[PUBLIC] Anonymous incident read {} by configuration", value ? "ENABLED" : "DISABLED");
    }

    /** Counts over the whole table, so "how many are open" is the table's answer. */
    public Stats stats() {
        Map<String, Long> byStatus = toCountMap(incidents.countGroupedByStatus());
        Map<String, Long> byPriority = toCountMap(incidents.countGroupedByPriority());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long open = byStatus.entrySet().stream()
                .filter(entry -> !CLOSED_STATES.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                .mapToLong(Map.Entry::getValue).sum();
        return new Stats(total, open, byStatus, byPriority, incidents.findLastUpdatedAt());
    }

    /** Blank {@code q} lists the most recently updated tickets. Never more than {@value #MAX_ROWS}. */
    public List<Row> search(String q) {
        String like = "%" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT)) + "%";
        return incidents.searchPublicRows(like, PageRequest.of(0, MAX_ROWS)).stream()
                .map(row -> new Row((String) row[0], (String) row[1], maskSensitive((String) row[2]), (String) row[3],
                        (String) row[4], (OffsetDateTime) row[5]))
                .toList();
    }

    /**
     * Masks PII, IP addresses, internal hosts, credentials and secret values in public views with '****'.
     */
    public static String maskSensitive(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text;
        // Passwords, secrets, tokens, keys, credentials
        s = s.replaceAll("(?i)(password|passwd|secret|api[_-]?key|token|bearer|auth|credential|pin)\\s*[:=]\\s*\\S+", "$1: ****");
        // Credentials inside URLs
        s = s.replaceAll("(?i)(https?://)[^:\\s]+:[^@\\s]+@", "$1****:****@");
        // Email addresses
        s = s.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "****@****");
        // IPv4 addresses
        s = s.replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "****");
        // IPv6 addresses
        s = s.replaceAll("\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b", "****");
        // Internal corporate hostnames / FQDNs (e.g. host.corp, server.internal, node-01.local)
        s = s.replaceAll("\\b[a-zA-Z0-9-]+\\.(?:corp|internal|local|lan|priv)\\b", "****.internal");
        // Store POS hostnames (e.g. store-0042-pos-01)
        s = s.replaceAll("(?i)\\bstore-\\d{2,6}-pos-\\d{1,4}\\b", "store-****-pos-**");
        // Credit card numbers
        s = s.replaceAll("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b", "****");
        // Phone numbers
        s = s.replaceAll("\\b(?:\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b", "****");
        // SSN
        s = s.replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "****");
        return s;
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        return counts;
    }

    /**
     * The only incident shape an anonymous caller ever receives. All PII and secret details
     * inside the description are masked with '****'.
     */
    public record Row(String externalId, String subject, String description, String status, String priority,
                      OffsetDateTime updatedAt) {}

    public record Stats(long total, long openCount, Map<String, Long> byStatus,
                        Map<String, Long> byPriority, OffsetDateTime updatedAt) {}
}
