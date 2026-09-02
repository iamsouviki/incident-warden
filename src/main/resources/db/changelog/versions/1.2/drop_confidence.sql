-- Confidence metrics removed from the schema.
--
-- With the autonomy surface already deleted, a confidence score gated exactly one thing:
-- whether a plan reached a human or was escalated away from one. Removing it leaves the
-- router with the rule the product actually wants — approved SOP present AND a known tool
-- means a human sees the plan — and deletes a six-factor weighted sum that no longer had
-- an audience.
--
-- Irreversible by design: keeping the column "just in case" is how a deleted metric comes
-- back six months later with nobody able to explain what its number means.

-- public.incidents (baseline.sql) is a SELECT * view, so Postgres expanded it to a fixed
-- column list at creation and it holds a hard dependency on confidence_score — the drop below
-- failed outright until this line existed. Dropped and recreated rather than dropped CASCADE:
-- CASCADE removes the view and puts nothing back, and recreating it after the columns are gone
-- is what re-expands the * to the reduced list.
DROP VIEW IF EXISTS public.incidents;

ALTER TABLE incident.incidents         DROP COLUMN IF EXISTS confidence_score;
ALTER TABLE incident.remediation_plans DROP COLUMN IF EXISTS confidence_score;

CREATE OR REPLACE VIEW public.incidents AS SELECT * FROM incident.incidents;

DELETE FROM config.system_config WHERE config_key LIKE 'mcp.confidence%';
