CREATE TABLE IF NOT EXISTS incident_conversation_threads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    incident_id UUID REFERENCES incidents(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    current_attempt INT NOT NULL DEFAULT 1,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    latest_proposal_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_incident_conversation_threads_tenant_updated
    ON incident_conversation_threads(tenant_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS incident_conversation_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL REFERENCES incident_conversation_threads(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    message_type VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    structured_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_incident_conversation_messages_thread_created
    ON incident_conversation_messages(thread_id, created_at ASC);

CREATE TABLE IF NOT EXISTS script_proposals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL REFERENCES incident_conversation_threads(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    incident_id UUID REFERENCES incidents(id) ON DELETE SET NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    shell_type VARCHAR(20) NOT NULL DEFAULT 'bash',
    target_ref VARCHAR(255),
    script_content TEXT NOT NULL,
    explanation TEXT,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    approval_required BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    rollback_plan TEXT,
    validation_plan_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    script_hash VARCHAR(128),
    created_by VARCHAR(100),
    approved_by VARCHAR(100),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_script_proposals_thread_created
    ON script_proposals(thread_id, created_at DESC);

ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS env_variables JSONB NOT NULL DEFAULT '[]'::jsonb;
