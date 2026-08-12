-- Tenant ownership for existing operational records.
ALTER TABLE incident.incidents ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
UPDATE incident.incidents SET tenant_id = 'tenant-1' WHERE tenant_id IS NULL;
ALTER TABLE incident.incidents ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_incidents_tenant ON incident.incidents(tenant_id);

ALTER TABLE incident.external_incidents ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
UPDATE incident.external_incidents SET tenant_id = 'tenant-1' WHERE tenant_id IS NULL;
ALTER TABLE incident.external_incidents ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_external_incidents_tenant ON incident.external_incidents(tenant_id);

CREATE TABLE IF NOT EXISTS incident.intake_records (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    source_system VARCHAR(100) NOT NULL,
    source_reference VARCHAR(255),
    fingerprint VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    incident_id UUID,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_intake_tenant_fingerprint ON incident.intake_records(tenant_id, fingerprint);

CREATE TABLE IF NOT EXISTS incident.confidence_logs (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    incident_id UUID NOT NULL,
    pattern_similarity DOUBLE PRECISION NOT NULL,
    historical_success DOUBLE PRECISION NOT NULL,
    sop_reliability DOUBLE PRECISION NOT NULL,
    system_health DOUBLE PRECISION NOT NULL,
    risk_penalty DOUBLE PRECISION NOT NULL,
    final_score DOUBLE PRECISION NOT NULL,
    evidence TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_confidence_incident ON incident.confidence_logs(incident_id, created_at DESC);

CREATE TABLE IF NOT EXISTS incident.remediation_plans (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    incident_id UUID NOT NULL,
    status VARCHAR(40) NOT NULL,
    action_name VARCHAR(120) NOT NULL,
    target VARCHAR(255) NOT NULL,
    parameters_json TEXT NOT NULL,
    sop_evidence TEXT,
    confidence_score DOUBLE PRECISION NOT NULL,
    risk_score DOUBLE PRECISION NOT NULL,
    guardrail_status VARCHAR(40) NOT NULL,
    guardrail_findings TEXT NOT NULL,
    rollback_plan TEXT NOT NULL,
    plan_hash VARCHAR(128) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_plan_incident ON incident.remediation_plans(incident_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_plan_tenant_status ON incident.remediation_plans(tenant_id, status);

CREATE TABLE IF NOT EXISTS incident.hitl_requests (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    incident_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    status VARCHAR(40) NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    reviewer VARCHAR(255),
    decision_reason TEXT,
    approved_plan_hash VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_hitl_open_plan ON incident.hitl_requests(plan_id, status);
CREATE INDEX IF NOT EXISTS idx_hitl_tenant_status ON incident.hitl_requests(tenant_id, status);

CREATE TABLE IF NOT EXISTS incident.action_executions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    incident_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    hitl_request_id UUID,
    mode VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    output TEXT,
    validation_result TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_action_execution_incident ON incident.action_executions(incident_id, started_at DESC);

CREATE TABLE IF NOT EXISTS incident.audit_events (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    previous_hash VARCHAR(128),
    event_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_aggregate ON incident.audit_events(tenant_id, aggregate_type, aggregate_id, created_at);
