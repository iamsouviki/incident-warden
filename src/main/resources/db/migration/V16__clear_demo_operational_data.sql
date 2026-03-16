-- V16: Remove remaining demo operational data that can still appear in the UI.
-- This clears the default demo tenant's incidents, HITL queue, audit log, KB,
-- and related confidence logs while preserving authored SOP/tool/script assets.

DELETE FROM audit_events
WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid;

DELETE FROM hitl_requests
WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
   OR incident_id IN (
        SELECT id FROM incidents
        WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
   );

DELETE FROM confidence_logs
WHERE incident_id IN (
    SELECT id FROM incidents
    WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
);

DELETE FROM resolved_incident_kb
WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid;

DELETE FROM incidents
WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid;
