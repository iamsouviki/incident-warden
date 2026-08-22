-- 1.16 — Notification delivery: who gets told, and how.
--
-- Two storage decisions here, deliberately different:
--
--   * Transport settings (relay host, port, from-address) go in config.system_config.
--     They describe one piece of deployment infrastructure shared by every tenant.
--   * Recipient lists go in their own table WITH a tenant_id. A global recipient list
--     would email tenant A's analysts about tenant B's incidents. Every other query in
--     this application is tenant-scoped; notifications must be too.
--
-- No credential column exists anywhere in this changeset. The relay is reached
-- unauthenticated on the internal network, which is what lets "configure it from the UI"
-- and "no auth details in the database" both hold at once. If a relay ever needs a
-- password, it belongs in an environment variable, not here.

-- ── Who reported the incident ─────────────────────────────────────────────────
-- Nullable on purpose: rows created before this column exist, and third-party exports
-- that carry no requester address, have no answer. A missing address means that
-- recipient is skipped, never that a fabricated one is used.
ALTER TABLE incident.incidents          ADD COLUMN IF NOT EXISTS reporter_email VARCHAR(320);
ALTER TABLE incident.external_incidents ADD COLUMN IF NOT EXISTS reporter_email VARCHAR(320);

-- ── Analyst recipients, per tenant ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS config.notification_recipient (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(255) NOT NULL,
    email       VARCHAR(320) NOT NULL,
    label       VARCHAR(255),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- One address cannot be registered twice for the same tenant; without this, a
-- double-click in the UI silently doubles every email that recipient receives.
CREATE UNIQUE INDEX IF NOT EXISTS notification_recipient_tenant_email_idx
    ON config.notification_recipient (tenant_id, lower(email));

CREATE INDEX IF NOT EXISTS notification_recipient_tenant_idx
    ON config.notification_recipient (tenant_id) WHERE enabled;

-- ── Transport + policy settings, editable from the UI ─────────────────────────
-- Seeded disabled. A fresh deployment that has never been configured must not attempt
-- to send mail to a host nobody chose.
INSERT INTO config.system_config (config_key, config_value) VALUES
    ('notify_enabled',   'false'),
    ('notify_smtp_host', ''),
    ('notify_smtp_port', '25'),
    ('notify_from',      'incident-automation@localhost'),
    ('autorun_enabled',  'false')
ON CONFLICT (config_key) DO NOTHING;

-- ── Remove the stored LLM credential ──────────────────────────────────────────
-- This row held a provider API key in plaintext. The key is now read from the
-- MCP_LLM_API_KEY environment variable only; AiConfigService no longer writes it and
-- the UI no longer offers a field for it. Deleting the row also purges any key that a
-- previous deployment already saved here.
DELETE FROM config.system_config WHERE config_key = 'api_key';
