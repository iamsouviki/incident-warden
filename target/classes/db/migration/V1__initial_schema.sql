-- V1__initial_schema.sql
-- Enable extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Tenants
CREATE TABLE tenants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  plan VARCHAR(50) DEFAULT 'STANDARD',
  auto_resolve_threshold DECIMAL(4,3) DEFAULT 1.000,
  hitl_threshold DECIMAL(4,3) DEFAULT 0.800,
  allow_p1_auto_resolve BOOLEAN DEFAULT false,
  max_blast_radius_pct INT DEFAULT 40,
  hitl_timeout_p1_min INT DEFAULT 15,
  hitl_timeout_p2_min INT DEFAULT 30,
  can_use_shared_sops BOOLEAN DEFAULT false,
  can_publish_sops BOOLEAN DEFAULT false,
  max_monthly_incidents INT DEFAULT 5000,
  max_sops INT DEFAULT 100,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Scheduler watermarks
CREATE TABLE scheduler_state (
  source_system VARCHAR(50) PRIMARY KEY,
  last_polled_at TIMESTAMPTZ DEFAULT '2000-01-01',
  last_run_at TIMESTAMPTZ,
  consecutive_errors INT DEFAULT 0
);

INSERT INTO scheduler_state(source_system) VALUES
  ('servicenow'), ('freshservice'), ('prometheus'), ('dynatrace');

-- Incidents (also the job queue)
CREATE TABLE incidents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  source_system VARCHAR(50) NOT NULL,
  source_ticket_id VARCHAR(100) NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  category VARCHAR(100),
  sub_category VARCHAR(100),
  severity VARCHAR(5) NOT NULL CHECK (severity IN ('P1','P2','P3','P4')),
  affected_systems TEXT[],
  status VARCHAR(40) DEFAULT 'PENDING',
  final_decision VARCHAR(20),
  retry_count INT DEFAULT 0,
  processing_started_at TIMESTAMPTZ,
  resolved_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE (source_system, source_ticket_id)
);

-- Critical index: makes queue queries fast even with millions of historical rows
CREATE INDEX idx_incidents_queue ON incidents(tenant_id, severity, created_at)
  WHERE status = 'PENDING';
CREATE INDEX idx_incidents_status ON incidents(status);

-- Classification rules (regex-based, editable by admins)
CREATE TABLE classification_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  pattern VARCHAR(500) NOT NULL,
  category VARCHAR(100) NOT NULL,
  sub_category VARCHAR(100),
  severity VARCHAR(5),
  confidence DECIMAL(4,3) NOT NULL,
  priority INT DEFAULT 100,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Incident patterns (learned from history)
CREATE TABLE incident_patterns (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  name VARCHAR(200) NOT NULL,
  description TEXT,
  category VARCHAR(100),
  sub_category VARCHAR(100),
  embedding vector(1536),
  tag_keywords TEXT[],
  occurrence_count INT DEFAULT 0,
  success_count INT DEFAULT 0,
  failure_count INT DEFAULT 0,
  avg_resolution_minutes INT,
  reliability_score DECIMAL(4,3) DEFAULT 1.0,
  last_matched_at TIMESTAMPTZ,
  is_active BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_patterns_embedding ON incident_patterns
  USING ivfflat (embedding vector_cosine_ops) WITH (lists=100);

-- SOP procedures
CREATE TABLE sop_procedures (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  scope VARCHAR(20) DEFAULT 'PRIVATE' CHECK (scope IN ('PRIVATE','SHARED','PLATFORM')),
  title VARCHAR(300) NOT NULL,
  version VARCHAR(20) NOT NULL DEFAULT 'v1.0',
  category VARCHAR(100),
  description TEXT,
  embedding vector(1536),
  action_plan_json JSONB,
  preconditions_json JSONB,
  rollback_steps_json JSONB,
  reliability_score DECIMAL(4,3) DEFAULT 1.0,
  success_count INT DEFAULT 0,
  failure_count INT DEFAULT 0,
  rejection_count INT DEFAULT 0,
  owner_team VARCHAR(100),
  approved_by VARCHAR(100),
  last_tested_at TIMESTAMPTZ,
  content_hash VARCHAR(64),
  status VARCHAR(30) DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','STALE','ARCHIVED')),
  updated_at TIMESTAMPTZ DEFAULT now(),
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_sop_embedding ON sop_procedures
  USING ivfflat (embedding vector_cosine_ops) WITH (lists=100);
CREATE INDEX idx_sop_tenant_status ON sop_procedures(tenant_id, status);

-- Pattern to SOP links
CREATE TABLE pattern_sop_links (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pattern_id UUID REFERENCES incident_patterns(id),
  sop_id UUID REFERENCES sop_procedures(id),
  success_rate DECIMAL(4,3) DEFAULT 1.0,
  usage_count INT DEFAULT 0
);

-- Confidence logs
CREATE TABLE confidence_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  incident_id UUID REFERENCES incidents(id),
  pattern_id UUID REFERENCES incident_patterns(id),
  sop_id UUID REFERENCES sop_procedures(id),
  score_pattern_sim DECIMAL(5,4),
  score_historical DECIMAL(5,4),
  score_sop_reliability DECIMAL(5,4),
  score_system_health DECIMAL(5,4),
  penalty_risk_factor DECIMAL(5,4),
  final_score DECIMAL(5,4),
  hard_override_applied BOOLEAN DEFAULT false,
  override_reason TEXT,
  decision VARCHAR(30),
  reasoning_text TEXT,
  computed_at TIMESTAMPTZ DEFAULT now()
);

-- HITL requests
CREATE TABLE hitl_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  incident_id UUID REFERENCES incidents(id),
  tenant_id UUID REFERENCES tenants(id),
  confidence_log_id UUID REFERENCES confidence_logs(id),
  status VARCHAR(20) DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED','MODIFIED','TIMED_OUT','ESCALATED')),
  approval_payload JSONB NOT NULL,
  decision VARCHAR(20),
  decided_by VARCHAR(100),
  decision_reason TEXT,
  modifications JSONB,
  outcome VARCHAR(30),
  expires_at TIMESTAMPTZ NOT NULL,
  decided_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- Action execution log
CREATE TABLE action_execution_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  incident_id UUID REFERENCES incidents(id),
  hitl_request_id UUID REFERENCES hitl_requests(id),
  tool_name VARCHAR(100) NOT NULL,
  step_number INT,
  parameters JSONB,
  result JSONB,
  pre_state JSONB,
  post_state JSONB,
  status VARCHAR(20) CHECK (status IN ('SUCCESS','FAILED','ROLLED_BACK','DRY_RUN')),
  is_dry_run BOOLEAN DEFAULT false,
  executed_by VARCHAR(100),
  duration_ms BIGINT,
  executed_at TIMESTAMPTZ DEFAULT now()
);

-- Audit events (immutable — tamper-evident)
CREATE TABLE audit_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  incident_id UUID,
  tenant_id UUID,
  trace_id VARCHAR(64),
  agent_id VARCHAR(100),
  event_type VARCHAR(100),
  event_payload JSONB,
  record_hash VARCHAR(64),
  created_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_insert_only ON audit_events FOR INSERT WITH CHECK (true);

-- Change freeze windows
CREATE TABLE change_windows (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  description VARCHAR(200),
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NOT NULL,
  is_active BOOLEAN DEFAULT true
);

-- Recent deployments
CREATE TABLE recent_deployments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  service_name VARCHAR(100) NOT NULL,
  version VARCHAR(100),
  deployed_by VARCHAR(100),
  deployed_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_deployments_service ON recent_deployments(tenant_id, service_name, deployed_at DESC);

-- SOP uploads
CREATE TABLE sop_uploads (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  file_name VARCHAR(255),
  file_path VARCHAR(500),
  file_type VARCHAR(20),
  parse_status VARCHAR(30) DEFAULT 'PENDING',
  parsed_json JSONB,
  error_message TEXT,
  uploaded_by VARCHAR(100),
  created_at TIMESTAMPTZ DEFAULT now()
);
