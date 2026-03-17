CREATE TABLE IF NOT EXISTS approved_remediation_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    incident_id UUID REFERENCES incidents(id) ON DELETE SET NULL,
    proposal_id UUID REFERENCES script_proposals(id) ON DELETE SET NULL,
    name VARCHAR(200) NOT NULL,
    service_name VARCHAR(120),
    environment_name VARCHAR(80) NOT NULL DEFAULT 'default',
    incident_fingerprint VARCHAR(255),
    shell_type VARCHAR(20) NOT NULL DEFAULT 'bash',
    action_class VARCHAR(50) NOT NULL DEFAULT 'manual_review',
    risk_level VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    auto_eligible BOOLEAN NOT NULL DEFAULT false,
    data_manipulation BOOLEAN NOT NULL DEFAULT false,
    embedding_ingested BOOLEAN NOT NULL DEFAULT false,
    script_content TEXT NOT NULL,
    script_hash VARCHAR(128),
    explanation TEXT,
    rollback_plan TEXT,
    validation_plan_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    success_count INT NOT NULL DEFAULT 1,
    failure_count INT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    approved_by VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_remediation_templates_tenant_updated
    ON approved_remediation_templates(tenant_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_remediation_templates_auto
    ON approved_remediation_templates(tenant_id, auto_eligible, risk_level);
