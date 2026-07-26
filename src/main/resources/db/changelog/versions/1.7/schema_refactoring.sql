-- ── Create target schemas ──────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS sop;
CREATE SCHEMA IF NOT EXISTS tools;
CREATE SCHEMA IF NOT EXISTS teams;
CREATE SCHEMA IF NOT EXISTS config;

-- ── Move vector_store table to sop schema ──────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'mcp_rag' AND tablename = 'vector_store') THEN
        ALTER TABLE mcp_rag.vector_store SET SCHEMA sop;
    ELSIF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'vector_store') THEN
        ALTER TABLE public.vector_store SET SCHEMA sop;
    ELSE
        CREATE TABLE IF NOT EXISTS sop.vector_store (
            id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
            content text,
            metadata jsonb,
            embedding vector(768),
            fts_vector tsvector
        );
    END IF;
END $$;

-- ── Move system_config table to config schema ──────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'system_config') THEN
        ALTER TABLE public.system_config SET SCHEMA config;
    ELSIF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'mcp_rag' AND tablename = 'system_config') THEN
        ALTER TABLE mcp_rag.system_config SET SCHEMA config;
    ELSE
        CREATE TABLE IF NOT EXISTS config.system_config (
            config_key VARCHAR(255) PRIMARY KEY,
            config_value TEXT
        );
    END IF;
END $$;

-- ── Move teams & team_employees tables to teams schema ──────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'incident' AND tablename = 'teams') THEN
        ALTER TABLE incident.teams SET SCHEMA teams;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'incident' AND tablename = 'team_employees') THEN
        ALTER TABLE incident.team_employees SET SCHEMA teams;
    END IF;
END $$;

-- ── Create tools tables ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tools.saved_scripts (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    script_content TEXT NOT NULL,
    language VARCHAR(50) NOT NULL,
    category VARCHAR(50) NOT NULL,
    target_host VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tools.execution_logs (
    id UUID PRIMARY KEY,
    script_id UUID,
    name VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    script_content TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    exit_code INTEGER NOT NULL,
    stdout TEXT,
    stderr TEXT
);

-- ── Create audit tables for all tables across all schemas ────────────────────
CREATE TABLE IF NOT EXISTS incident.incidents_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS incident.incident_comments_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS incident.external_incidents_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS incident.statuses_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS sop.vector_store_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS tools.saved_scripts_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS tools.execution_logs_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS teams.teams_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS teams.team_employees_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

CREATE TABLE IF NOT EXISTS config.system_config_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type VARCHAR(10) NOT NULL,
    changed_by VARCHAR(255),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    row_data JSONB
);

-- ── Create universal audit trigger function ─────────────────────────────────
CREATE OR REPLACE FUNCTION audit_log_trigger_func() RETURNS TRIGGER AS $$
DECLARE
    old_data JSONB := NULL;
    new_data JSONB := NULL;
    op_name TEXT := TG_OP;
BEGIN
    IF (op_name = 'UPDATE') THEN
        old_data := to_jsonb(OLD);
        new_data := to_jsonb(NEW);
    ELSIF (op_name = 'DELETE') THEN
        old_data := to_jsonb(OLD);
    ELSIF (op_name = 'INSERT') THEN
        new_data := to_jsonb(NEW);
    END IF;

    EXECUTE format('INSERT INTO %I.%I (action_type, changed_by, changed_at, row_data) VALUES ($1, $2, $3, $4)', 
                   TG_TABLE_SCHEMA, TG_TABLE_NAME || '_audit')
    USING op_name, current_user, current_timestamp, 
          CASE WHEN op_name = 'DELETE' THEN old_data ELSE new_data END;

    IF (op_name = 'DELETE') THEN
        RETURN OLD;
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ── Register audit triggers on all tables ───────────────────────────────────

-- incident schema
DROP TRIGGER IF EXISTS trg_incidents_audit ON incident.incidents;
CREATE TRIGGER trg_incidents_audit AFTER INSERT OR UPDATE OR DELETE ON incident.incidents
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

DROP TRIGGER IF EXISTS trg_incident_comments_audit ON incident.incident_comments;
CREATE TRIGGER trg_incident_comments_audit AFTER INSERT OR UPDATE OR DELETE ON incident.incident_comments
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

DROP TRIGGER IF EXISTS trg_external_incidents_audit ON incident.external_incidents;
CREATE TRIGGER trg_external_incidents_audit AFTER INSERT OR UPDATE OR DELETE ON incident.external_incidents
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

DROP TRIGGER IF EXISTS trg_statuses_audit ON incident.statuses;
CREATE TRIGGER trg_statuses_audit AFTER INSERT OR UPDATE OR DELETE ON incident.statuses
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

-- sop schema
DROP TRIGGER IF EXISTS trg_vector_store_audit ON sop.vector_store;
CREATE TRIGGER trg_vector_store_audit AFTER INSERT OR UPDATE OR DELETE ON sop.vector_store
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

-- tools schema
DROP TRIGGER IF EXISTS trg_saved_scripts_audit ON tools.saved_scripts;
CREATE TRIGGER trg_saved_scripts_audit AFTER INSERT OR UPDATE OR DELETE ON tools.saved_scripts
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

DROP TRIGGER IF EXISTS trg_execution_logs_audit ON tools.execution_logs;
CREATE TRIGGER trg_execution_logs_audit AFTER INSERT OR UPDATE OR DELETE ON tools.execution_logs
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

-- teams schema
DROP TRIGGER IF EXISTS trg_teams_audit ON teams.teams;
CREATE TRIGGER trg_teams_audit AFTER INSERT OR UPDATE OR DELETE ON teams.teams
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

DROP TRIGGER IF EXISTS trg_team_employees_audit ON teams.team_employees;
CREATE TRIGGER trg_team_employees_audit AFTER INSERT OR UPDATE OR DELETE ON teams.team_employees
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();

-- config schema
DROP TRIGGER IF EXISTS trg_system_config_audit ON config.system_config;
CREATE TRIGGER trg_system_config_audit AFTER INSERT OR UPDATE OR DELETE ON config.system_config
FOR EACH ROW EXECUTE FUNCTION audit_log_trigger_func();
