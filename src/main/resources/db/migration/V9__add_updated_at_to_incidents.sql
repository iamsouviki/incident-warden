-- V9: Add missing updated_at column to incidents table.
-- The StaleJobRecoveryScheduler (and future auditing) expects this column,
-- but it was omitted from the original V1 schema.
-- Backfill existing rows with created_at so the timestamp is meaningful.

ALTER TABLE incidents
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

UPDATE incidents
SET updated_at = created_at
WHERE updated_at IS NULL;

-- Keep it in sync automatically going forward.
CREATE OR REPLACE FUNCTION incidents_set_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_incidents_updated_at ON incidents;
CREATE TRIGGER trg_incidents_updated_at
    BEFORE UPDATE ON incidents
    FOR EACH ROW EXECUTE FUNCTION incidents_set_updated_at();
