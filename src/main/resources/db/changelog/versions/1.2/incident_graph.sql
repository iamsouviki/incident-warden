-- ─────────────────────────────────────────────────────────────────────────────
-- Knowledge graph for relationship mapping.
--
-- It is a view, not a graph database. Every relationship this platform can state
-- about an incident is already a column or a JSON field on a row it owns:
--
--   the machine       incidents.target_host
--   the site          incidents.store_number
--   the category      incidents.category
--   the remediation   remediation_plans.action_name
--   the procedure     remediation_plans.parameters_json -> procedureIds
--   the past ticket   remediation_plans.parameters_json -> precedent.incidentId
--
-- Copying those into a second store to call them nodes and edges would add a
-- sync problem, a consistency question and a licence, and answer nothing this
-- view cannot. "Every incident on this host" and "every incident this procedure
-- has ever grounded" are joins. Traversal deeper than one hop is a recursive
-- CTE against this view -- see IncidentGraphService.
--
-- Edges are emitted in both directions so a traversal joins on src_key alone.
--
-- Node keys are text ('HOST:store-0042-app-01'), because a host and a store are
-- not rows anywhere and inventing a table to give them UUIDs would be inventing
-- an inventory system.
--
-- ponytail: a plain view, recomputed per query. At ~10^5 incidents that is a
-- few sequential scans and fine. If it stops being fine, this becomes
-- `CREATE MATERIALIZED VIEW` plus a REFRESH on the same schedule as the chat
-- cleanup job -- the query below does not change.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW incident.graph_edges AS
WITH directed AS (
    -- The incident itself, so a lone ticket with no plan still has a node.
    SELECT 'INCIDENT:' || i.id                            AS src_key,
           'INCIDENT'                                     AS src_type,
           COALESCE(NULLIF(i.external_id, ''), i.subject) AS src_label,
           'OCCURRED_ON'                                  AS edge,
           'HOST:' || i.target_host                       AS dst_key,
           'HOST'                                         AS dst_type,
           i.target_host                                  AS dst_label
    FROM incident.incidents i
    WHERE COALESCE(i.target_host, '') <> ''

    UNION ALL
    SELECT 'INCIDENT:' || i.id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'AT_STORE', 'STORE:' || i.store_number, 'STORE', i.store_number
    FROM incident.incidents i
    WHERE COALESCE(i.store_number, '') <> ''

    UNION ALL
    SELECT 'INCIDENT:' || i.id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'CLASSIFIED_AS', 'CATEGORY:' || i.category, 'CATEGORY', i.category
    FROM incident.incidents i
    WHERE COALESCE(i.category, '') <> ''

    -- The remediation that was planned for it, whether or not it was approved.
    -- A blocked plan's action is still a relationship: it is how an operator
    -- finds every ticket that wanted a restart and did not get one.
    UNION ALL
    SELECT 'INCIDENT:' || p.incident_id, 'INCIDENT',
           COALESCE(NULLIF(i.external_id, ''), i.subject),
           'PLANNED', 'ACTION:' || p.action_name, 'ACTION', p.action_name
    FROM incident.remediation_plans p
    JOIN incident.incidents i ON i.id = p.incident_id
    WHERE p.action_name <> 'none'

    -- The approved procedures the plan was grounded in. procedureIds is written
    -- by the planner as a JSON array of UUID strings; the LIKE guard keeps a
    -- legacy or truncated parameters_json out of the jsonb cast.
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

    -- The past ticket cited as justification. Incident-to-incident, which is the
    -- edge that makes this a graph rather than a star of lookup tables.
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

-- The three lookups the traversal makes. Nothing else in the schema indexed
-- these because nothing else joined on them.
CREATE INDEX IF NOT EXISTS idx_incidents_target_host  ON incident.incidents(target_host);
CREATE INDEX IF NOT EXISTS idx_incidents_store_number ON incident.incidents(store_number);
CREATE INDEX IF NOT EXISTS idx_plans_incident         ON incident.remediation_plans(incident_id);
