-- ─────────────────────────────────────────────────────────────────────────────
-- Single organisation: drop the tenant concept from every table that carried it.
--
-- Forward migration for databases that were already stood up with these columns.
-- baseline.sql no longer creates them, so a fresh install runs this as a no-op.
--
-- The graph view is dropped first and rebuilt by the changeset that follows,
-- because a view that selects tenant_id blocks the DROP COLUMN behind it.
-- ─────────────────────────────────────────────────────────────────────────────

DROP VIEW IF EXISTS incident.graph_edges;

DROP INDEX IF EXISTS ai.idx_chat_sessions_tenant_user;
DROP INDEX IF EXISTS incident.idx_incidents_tenant_host;
DROP INDEX IF EXISTS incident.idx_incidents_tenant_store;

ALTER TABLE auth.users                  DROP COLUMN IF EXISTS tenant_id,
                                        DROP COLUMN IF EXISTS tenant_name;
ALTER TABLE sop.sop_procedure           DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE incident.incidents          DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE incident.remediation_plans  DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE incident.hitl_requests      DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE incident.action_executions  DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE incident.audit_events       DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE incident.telemetry_events   DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE tools.saved_scripts         DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE tools.skills                DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE ai.chat_sessions            DROP COLUMN IF EXISTS tenant_id;

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user ON ai.chat_sessions(username, updated_at DESC);

-- The committed BCrypt hash of 'admin' that changelog 1.0 used to seed. Any account still
-- holding it is holding a credential published in this repository's history; clearing the hash
-- makes it unauthenticatable and hands the account back to BootstrapPassword, which enrols it
-- on username-as-starter-password with a forced change. See BootstrapPassword.
UPDATE auth.users
SET password_hash = NULL, must_change_password = TRUE
WHERE password_hash = '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG';

-- Creating accounts is owner-only, so the seeded administrator has to be an OWNER or a fresh
-- install can never add its first colleague.
UPDATE auth.users SET role = 'OWNER' WHERE username = 'admin' AND role = 'ADMIN';

-- Web search is gone from the application: AiConfigService no longer reads or writes this key,
-- so the row is an orphan that only reads as if the feature could come back. Delete it.
DELETE FROM config.system_config WHERE config_key = 'web_search_enabled';
