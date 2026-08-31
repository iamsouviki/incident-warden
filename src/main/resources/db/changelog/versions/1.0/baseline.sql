-- ── Consolidated Database Baseline Schema ──────────────────────────────────────

-- 1. Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Schemas
CREATE SCHEMA IF NOT EXISTS mcp_rag;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS sop;
CREATE SCHEMA IF NOT EXISTS hitl;
CREATE SCHEMA IF NOT EXISTS incident;
CREATE SCHEMA IF NOT EXISTS tools;
CREATE SCHEMA IF NOT EXISTS teams;
CREATE SCHEMA IF NOT EXISTS config;
CREATE SCHEMA IF NOT EXISTS ai;

-- 3. Audit Trigger Function
CREATE OR REPLACE FUNCTION audit_log_trigger_func()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO auth.users_audit (action_type, changed_by, changed_at, row_data)
    VALUES (TG_OP, CURRENT_USER, CURRENT_TIMESTAMP, row_to_json(NEW)::jsonb);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Auth: Users & Audit
CREATE TABLE IF NOT EXISTS auth.users (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username             VARCHAR(100) NOT NULL UNIQUE,
    full_name            VARCHAR(255),
    email                VARCHAR(255),
    department           VARCHAR(255),
    password_hash        VARCHAR(255),
    role                 VARCHAR(50)  NOT NULL DEFAULT 'VIEWER',
    tenant_id            VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    tenant_name          VARCHAR(255)          DEFAULT 'Primary Workspace',
    sso_provider         VARCHAR(50),
    sso_subject          VARCHAR(255),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS auth.users_audit (
    audit_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10)  NOT NULL,
    changed_by  VARCHAR(255),
    changed_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data    JSONB
);

DROP TRIGGER IF EXISTS trg_users_audit ON auth.users;
CREATE TRIGGER trg_users_audit
AFTER INSERT OR UPDATE OR DELETE ON auth.users
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

-- Seed single default owner admin user (Username: admin / Password: admin)
-- $2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG is BCrypt for 'admin'
INSERT INTO auth.users (username, email, full_name, password_hash, role, tenant_id, tenant_name, must_change_password)
VALUES ('admin', 'admin@mcp.local', 'System Administrator', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'ADMIN', 'tenant-1', 'Primary Workspace', true)
ON CONFLICT (username) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    role = EXCLUDED.role,
    must_change_password = true;

-- 5. SOP: Vector Store & Procedures
CREATE TABLE IF NOT EXISTS sop.vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT NOT NULL,
    metadata  JSONB,
    embedding vector(768)
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding ON sop.vector_store USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_vector_store_content_fts ON sop.vector_store USING gin (to_tsvector('english', content));

CREATE TABLE IF NOT EXISTS sop.sop_procedure (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'tenant-1',
    sop_id            VARCHAR(64)  NOT NULL,
    step_number       INT          NOT NULL DEFAULT 1,
    title             VARCHAR(300) NOT NULL,
    description       VARCHAR(4000),
    match_keywords    VARCHAR(1000),
    action_key        VARCHAR(500) NOT NULL,
    approval_status   VARCHAR(32)  NOT NULL DEFAULT 'APPROVED',
    requires_approval BOOLEAN      NOT NULL DEFAULT TRUE,
    execution_order   INT          NOT NULL DEFAULT 10,
    reliability       DOUBLE PRECISION NOT NULL DEFAULT 0.70,
    success_count     INT          NOT NULL DEFAULT 0,
    failure_count     INT          NOT NULL DEFAULT 0,
    approved_by       VARCHAR(150),
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 6. Incident: Internal & External Tickets
CREATE TABLE IF NOT EXISTS incident.incidents (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    subject            VARCHAR(255) NOT NULL,
    description        TEXT,
    assignee           VARCHAR(100),
    assigned_gteam     VARCHAR(100),
    priority           VARCHAR(20)  NOT NULL DEFAULT 'P3',
    status             VARCHAR(50)  NOT NULL DEFAULT 'PENDING_ANALYSIS',
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    due_date           TIMESTAMP WITH TIME ZONE,
    external_source    VARCHAR(50)  DEFAULT 'Internal',
    external_id        VARCHAR(100) UNIQUE,
    category           VARCHAR(100) DEFAULT 'General',
    confidence_score   DOUBLE PRECISION DEFAULT 0.0,
    reporter_email     VARCHAR(255),
    store_number       VARCHAR(50),
    target_host        VARCHAR(255),
    connection_method  VARCHAR(50),
    target_platform    VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS incident.external_incidents (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    subject            VARCHAR(255) NOT NULL,
    description        TEXT,
    assignee           VARCHAR(100),
    assigned_gteam     VARCHAR(100),
    priority           VARCHAR(20)  NOT NULL DEFAULT 'P3',
    status             VARCHAR(50)  NOT NULL DEFAULT 'NEW',
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    due_date           TIMESTAMP WITH TIME ZONE,
    external_source    VARCHAR(50)  NOT NULL,
    external_id        VARCHAR(100) NOT NULL,
    category           VARCHAR(100) DEFAULT 'General',
    confidence_score   DOUBLE PRECISION DEFAULT 0.0,
    reporter_email     VARCHAR(255),
    CONSTRAINT uq_ext_tenant UNIQUE (external_id, external_source, tenant_id)
);

CREATE TABLE IF NOT EXISTS incident.incident_comments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id   UUID NOT NULL,
    author        VARCHAR(100) NOT NULL,
    comment_text  TEXT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS incident.incident_history (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id UUID NOT NULL,
    field_name  VARCHAR(50) NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    updated_by  VARCHAR(100) NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS incident.statuses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    is_terminal BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS incident.remediation_plans (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    incident_id        UUID NOT NULL,
    status             VARCHAR(50) NOT NULL,
    action_name        VARCHAR(100) NOT NULL,
    target             VARCHAR(255) NOT NULL,
    parameters_json    TEXT NOT NULL,
    sop_evidence       TEXT,
    confidence_score   DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    risk_score         DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    guardrail_status   VARCHAR(50) NOT NULL DEFAULT 'PASS',
    guardrail_findings TEXT NOT NULL DEFAULT '',
    rollback_plan      TEXT NOT NULL DEFAULT '',
    remediation_script TEXT,
    script_language    VARCHAR(50),
    script_source      VARCHAR(50),
    script_scan_level  VARCHAR(50),
    plan_hash          VARCHAR(255) NOT NULL,
    attempts           INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS incident.hitl_requests (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    incident_id        UUID NOT NULL,
    plan_id            UUID NOT NULL,
    status             VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    requested_by       VARCHAR(100) NOT NULL,
    reviewer           VARCHAR(100),
    decision_reason    TEXT,
    approved_plan_hash VARCHAR(255),
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    decided_at         TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS incident.action_executions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    incident_id       UUID NOT NULL,
    plan_id           UUID NOT NULL,
    hitl_request_id   UUID,
    mode              VARCHAR(50) NOT NULL,
    status            VARCHAR(50) NOT NULL,
    output            TEXT,
    validation_result TEXT,
    started_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS incident.audit_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    details    JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS incident.telemetry_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_name VARCHAR(100) NOT NULL,
    payload    JSONB,
    tenant_id  VARCHAR(100) DEFAULT 'tenant-1',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Backward compatibility views in public schema
CREATE OR REPLACE VIEW public.incidents AS SELECT * FROM incident.incidents;
CREATE OR REPLACE VIEW public.external_incidents AS SELECT * FROM incident.external_incidents;

-- 7. Tools: Saved Scripts, Execution Logs, MCP Server, Skills
CREATE TABLE IF NOT EXISTS tools.saved_scripts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    script_content TEXT NOT NULL,
    language       VARCHAR(50) NOT NULL,
    category       VARCHAR(100) NOT NULL,
    target_host    VARCHAR(255) NOT NULL,
    tenant_id      VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tools.execution_logs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    script_id         UUID,
    incident_id       UUID,
    agent             VARCHAR(100),
    phase             VARCHAR(100),
    validation_status VARCHAR(100),
    name              VARCHAR(255) NOT NULL,
    timestamp         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    script_content    TEXT NOT NULL,
    status            VARCHAR(50) NOT NULL,
    exit_code         INT NOT NULL,
    stdout            TEXT,
    stderr            TEXT
);

CREATE TABLE IF NOT EXISTS tools.mcp_server (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'tenant-1',
    name        VARCHAR(150) NOT NULL,
    transport   VARCHAR(32)  NOT NULL DEFAULT 'http',
    endpoint    VARCHAR(500) NOT NULL,
    description VARCHAR(1000),
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(150),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tools.skills (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'tenant-1',
    kind        VARCHAR(24)  NOT NULL,
    skill_key   VARCHAR(120) NOT NULL,
    pattern     VARCHAR(600),
    action_key  VARCHAR(120),
    arg_count   INT          NOT NULL DEFAULT 0,
    mutating    BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    description VARCHAR(600),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(120)
);

-- 8. Teams: Teams & Team Employees
CREATE TABLE IF NOT EXISTS teams.teams (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    email       VARCHAR(255),
    tenant_id   VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS teams.team_employees (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username    VARCHAR(100) NOT NULL UNIQUE,
    full_name   VARCHAR(255),
    email       VARCHAR(255),
    role        VARCHAR(100),
    department  VARCHAR(100),
    team_id     UUID REFERENCES teams.teams(id) ON DELETE CASCADE
);

-- 9. Config: System Config & AI Configuration
CREATE TABLE IF NOT EXISTS config.system_config (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value TEXT NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai.ai_config (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider        VARCHAR(50) NOT NULL,
    chat_model      VARCHAR(100),
    embedding_model VARCHAR(100),
    base_url        VARCHAR(255),
    api_key         VARCHAR(255),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai.ai_config (provider, chat_model, embedding_model, base_url, active)
VALUES ('ollama', 'qwen2.5-coder:3b', 'nomic-embed-text:latest', 'http://localhost:11434', true)
ON CONFLICT DO NOTHING;
