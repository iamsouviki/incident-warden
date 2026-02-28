-- V4: Fix column types for compatibility with JPA mappings

-- Change audit_events.event_payload from JSONB to TEXT
-- (AuditEvent entity stores JSON as String)
ALTER TABLE audit_events
    ALTER COLUMN event_payload TYPE TEXT USING event_payload::TEXT;

-- Change hitl_requests.approval_payload from JSONB to TEXT if it exists
ALTER TABLE hitl_requests
    ALTER COLUMN approval_payload TYPE TEXT USING approval_payload::TEXT;

-- Change sop_procedures.action_plan_json from JSONB to TEXT
ALTER TABLE sop_procedures
    ALTER COLUMN action_plan_json TYPE TEXT USING action_plan_json::TEXT;

ALTER TABLE sop_procedures
    ALTER COLUMN preconditions_json TYPE TEXT USING preconditions_json::TEXT;

ALTER TABLE sop_procedures
    ALTER COLUMN rollback_steps_json TYPE TEXT USING rollback_steps_json::TEXT;
