-- ─────────────────────────────────────────────────────────────────────────────
-- GraphRAG Expansion: Richer Relationships & Multi-hop Traversal
-- ─────────────────────────────────────────────────────────────────────────────

DROP VIEW IF EXISTS incident.graph_edges CASCADE;

CREATE OR REPLACE VIEW incident.graph_edges AS
WITH directed AS (
    -- 1. Base Incident to Target Host
    SELECT 'INCIDENT:' || i.id                            AS src_key,
           'INCIDENT'                                     AS src_type,
           COALESCE(NULLIF(i.external_id, ''), i.subject) AS src_label,
           'OCCURRED_ON'                                  AS edge,
           'HOST:' || i.target_host                       AS dst_key,
           'HOST'                                         AS dst_type,
           i.target_host                                  AS dst_label
    FROM incident.incidents i
    WHERE COALESCE(i.target_host, '') <> ''

    -- 2. Base Incident to Store Number
    UNION ALL
    SELECT 'INCIDENT:' || i.id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'AT_STORE', 'STORE:' || i.store_number, 'STORE', i.store_number
    FROM incident.incidents i
    WHERE COALESCE(i.store_number, '') <> ''

    -- 3. Incident to Category
    UNION ALL
    SELECT 'INCIDENT:' || i.id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'CLASSIFIED_AS', 'CATEGORY:' || i.category, 'CATEGORY', i.category
    FROM incident.incidents i
    WHERE COALESCE(i.category, '') <> ''

    -- 4. Incident to Severity / Priority
    UNION ALL
    SELECT 'INCIDENT:' || i.id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'SEVERITY_LEVEL', 'SEVERITY:' || i.priority, 'SEVERITY', i.priority
    FROM incident.incidents i
    WHERE COALESCE(i.priority, '') <> ''

    -- 5. Incident to Lifecycle Status
    UNION ALL
    SELECT 'INCIDENT:' || i.id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'HAS_STATUS', 'STATUS:' || i.status, 'STATUS', i.status
    FROM incident.incidents i
    WHERE COALESCE(i.status, '') <> ''

    -- 6. Remediation Plan Action
    UNION ALL
    SELECT 'INCIDENT:' || p.incident_id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'PLANNED', 'ACTION:' || p.action_name, 'ACTION', p.action_name
    FROM incident.remediation_plans p
    JOIN incident.incidents i ON i.id = p.incident_id
    WHERE p.action_name <> 'none'

    -- 7. Grounded SOP Procedures
    UNION ALL
    SELECT 'INCIDENT:' || p.incident_id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'GROUNDED_IN', 'SOP:' || s.id, 'SOP', s.title
    FROM incident.remediation_plans p
    JOIN incident.incidents i ON i.id = p.incident_id
    CROSS JOIN LATERAL jsonb_array_elements_text(
            COALESCE(p.parameters_json::jsonb -> 'procedureIds', '[]'::jsonb)) AS pid(value)
    JOIN sop.sop_procedure s ON s.id = pid.value::uuid
    WHERE p.parameters_json LIKE '{%'

    -- 8. Incident Precedent
    UNION ALL
    SELECT 'INCIDENT:' || p.incident_id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'PRECEDENT', 'INCIDENT:' || (p.parameters_json::jsonb #>> '{precedent,incidentId}'),
           'INCIDENT',
           COALESCE(NULLIF(prior.external_id, ''), prior.subject)
    FROM incident.remediation_plans p
    JOIN incident.incidents i ON i.id = p.incident_id
    JOIN incident.incidents prior
      ON prior.id = (p.parameters_json::jsonb #>> '{precedent,incidentId}')::uuid
    WHERE p.parameters_json LIKE '{%'
      AND p.parameters_json::jsonb #>> '{precedent,incidentId}' IS NOT NULL
)
SELECT DISTINCT src_key, src_type, src_label, edge, dst_key, dst_type, dst_label
FROM directed
UNION
SELECT DISTINCT dst_key, dst_type, dst_label, edge, src_key, src_type, src_label
FROM directed;
