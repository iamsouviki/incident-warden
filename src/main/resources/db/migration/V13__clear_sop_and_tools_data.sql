-- V13: Clear all seeded SOP and MCP Tools data
-- Handles FK constraints in the correct order before truncating parent tables.

-- 1. Null out FK references in incidents → sop_procedures
UPDATE incidents SET matched_sop_id = NULL WHERE matched_sop_id IS NOT NULL;

-- 2. Remove confidence_log rows that reference sop_procedures
DELETE FROM confidence_logs WHERE sop_id IS NOT NULL;

-- 3. Remove pattern→SOP links
DELETE FROM pattern_sop_links WHERE sop_id IS NOT NULL;

-- 4. Now safe to clear all SOP data
DELETE FROM sop_procedures;

-- 5. Clear all custom MCP tool overrides/additions
DELETE FROM custom_tools;
