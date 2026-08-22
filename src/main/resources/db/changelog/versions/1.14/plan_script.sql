-- The approved script becomes part of the plan. A reviewer approves the exact text that
-- will run, and that text is inside the plan hash, so an edit invalidates the approval.

ALTER TABLE incident.remediation_plans
    ADD COLUMN IF NOT EXISTS remediation_script TEXT,
    ADD COLUMN IF NOT EXISTS script_language    VARCHAR(32),
    -- SOP_TEMPLATE | SOP_GROUNDED | LLM_KNOWLEDGE | NONE. Not a CHECK constraint: a new
    -- provenance tier should not require a migration before it can be recorded honestly.
    ADD COLUMN IF NOT EXISTS script_source      VARCHAR(32),
    ADD COLUMN IF NOT EXISTS script_scan_level  VARCHAR(16);
