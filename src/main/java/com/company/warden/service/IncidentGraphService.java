package com.company.warden.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the incident knowledge graph: which machines, sites, procedures, remediations and
 * past tickets an incident is connected to, and how they connect to each other.
 *
 * <p>The graph is the view {@code incident.graph_edges} — every edge is derived from a row
 * this platform already owns, so there is nothing to ingest and nothing that can drift out
 * of date. See {@code db/changelog/versions/1.2/incident_graph.sql}.
 *
 * <p>Traversal is one recursive CTE. Two hops is the useful depth and the default: hop one
 * reaches the host, the store, the procedure and the remediation; hop two reaches every
 * other incident that shares any of them, which is the question an operator is really
 * asking ("has this happened elsewhere, and what fixed it?"). Depth is capped at three
 * because the edge set is symmetric — a third hop from a busy host pulls in most of the
 * estate and answers nothing.
 */
@Service
public class IncidentGraphService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IncidentGraphService.class);

    /**
     * Edges among everything reachable from the root within {@code depth} hops.
     *
     * <p>{@code src_key < dst_key} collapses the view's two directions to one row per edge.
     * The LIMIT is a backstop, not a page: a neighbourhood that needs more than 500 edges is
     * not a diagram anyone reads, and truncation is reported to the caller.
     */
    private static final String NEIGHBOURHOOD = """
            WITH RECURSIVE reachable(node_key, depth) AS (
                    SELECT CAST(? AS text), 0
                UNION
                    SELECT e.dst_key, r.depth + 1
                    FROM reachable r
                    JOIN incident.graph_edges e ON e.src_key = r.node_key
                    WHERE r.depth < ?
            )
            SELECT DISTINCT e.src_key, e.src_type, e.src_label, e.edge, e.dst_key, e.dst_type, e.dst_label
            FROM incident.graph_edges e
            JOIN reachable a ON a.node_key = e.src_key
            JOIN reachable b ON b.node_key = e.dst_key
            WHERE e.src_key < e.dst_key
            LIMIT 501
            """;

    private static final int MAX_EDGES = 500;

    private final JdbcTemplate jdbc;

    public IncidentGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param depth hops from the incident, clamped to [1,3].
     * @return {@code {nodes:[{key,type,label}], edges:[{source,edge,target}], truncated:boolean}}.
     *         Empty when the incident has no mapped relationships, and empty for an id that
     *         does not exist — an unknown root reaches nothing.
     */
    public Map<String, Object> neighbourhood(UUID incidentId, int depth) {
        int hops = Math.max(1, Math.min(3, depth));
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(NEIGHBOURHOOD, "INCIDENT:" + incidentId, hops);
        } catch (Exception e) {
            // A missing view (a deployment that has not run 1.2-incident-graph yet) must not
            // take the incident page down with it. The relationship panel goes empty instead.
            log.warn("[GRAPH] Neighbourhood query failed for {}: {}", incidentId, e.getMessage());
            return Map.of("nodes", List.of(), "edges", List.of(), "truncated", false);
        }

        boolean truncated = rows.size() > MAX_EDGES;
        if (truncated) rows = rows.subList(0, MAX_EDGES);

        // Nodes are the endpoints of the edges — each row already carries the type and label
        // of both ends, so there is no second query and no node table.
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Set<Map<String, Object>> edges = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            node(nodes, row, "src");
            node(nodes, row, "dst");
            edges.add(Map.of("source", String.valueOf(row.get("src_key")),
                    "edge", String.valueOf(row.get("edge")),
                    "target", String.valueOf(row.get("dst_key"))));
        }
        return Map.of("nodes", List.copyOf(nodes.values()), "edges", List.copyOf(edges),
                "truncated", truncated);
    }

    private void node(Map<String, Map<String, Object>> nodes, Map<String, Object> row, String side) {
        String key = String.valueOf(row.get(side + "_key"));
        nodes.putIfAbsent(key, Map.of("key", key,
                "type", String.valueOf(row.get(side + "_type")),
                "label", String.valueOf(row.get(side + "_label"))));
    }
}
