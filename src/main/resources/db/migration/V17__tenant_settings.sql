CREATE TABLE IF NOT EXISTS tenant_settings (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    incident_sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    llm_providers JSONB NOT NULL DEFAULT '[]'::jsonb,
    incident_defaults JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tenant_settings_updated_at ON tenant_settings(updated_at DESC);
