-- Backfill for rows created before external ids were mandatory.
--
-- This replaces a @PostConstruct in IncidentService that ran findAll() over the whole
-- incidents table on every application start and saved each row it touched. The work is
-- a one-off migration, so it belongs here: Liquibase runs it once and records that it
-- did, instead of paying for a full table scan on every boot forever.

UPDATE incident.incidents
   SET external_source = 'Internal'
 WHERE external_source IS NULL OR external_source = 'None';

UPDATE incident.external_incidents
   SET external_source = 'Internal'
 WHERE external_source IS NULL OR external_source = 'None';

-- Numbering continues from the highest INC number already issued in either table, so a
-- re-run on a partially-backfilled database cannot mint a duplicate.
WITH high AS (
    SELECT COALESCE(MAX(n), 0) AS n FROM (
        SELECT MAX(CAST(SUBSTRING(external_id FROM 4) AS BIGINT)) AS n
          FROM incident.incidents
         WHERE external_id ~ '^INC[0-9]{9}$'
        UNION ALL
        SELECT MAX(CAST(SUBSTRING(external_id FROM 4) AS BIGINT)) AS n
          FROM incident.external_incidents
         WHERE external_id ~ '^INC[0-9]{9}$'
    ) AS both_tables
), numbered AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at NULLS LAST, id) AS rn
      FROM incident.incidents
     WHERE external_id IS NULL OR external_id = ''
)
UPDATE incident.incidents i
   SET external_id = 'INC' || LPAD((numbered.rn + high.n)::TEXT, 9, '0')
  FROM numbered, high
 WHERE i.id = numbered.id;
