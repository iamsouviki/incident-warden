-- V11: Resolved Incident Knowledge Base
-- A dedicated table that archives every resolved incident along with its
-- resolution details, operator comments, and metadata.  This table is used
-- as an additional RAG source so the system can suggest solutions for NEW
-- incidents based on how SIMILAR incidents were fixed in the past.
-- -------------------------------------------------------------------------

CREATE TABLE resolved_incident_kb (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Original incident linkage (nullable - original row may be deleted)
    incident_id        UUID,
    tenant_id          UUID        NOT NULL,
    source_system      VARCHAR(50) NOT NULL,
    source_ticket_id   VARCHAR(100),

    -- Incident description
    title              TEXT        NOT NULL,
    description        TEXT,
    category           VARCHAR(100),
    sub_category       VARCHAR(100),
    severity           VARCHAR(5)  NOT NULL,           -- P1/P2/P3/P4
    affected_systems   TEXT[],

    -- Resolution knowledge
    resolution_summary TEXT,                           -- brief TL;DR of fix
    root_cause         TEXT,                           -- identified root cause
    resolution_steps   JSONB       NOT NULL DEFAULT '[]'::JSONB,
    -- Each step: { "step": 1, "action": "...", "tool": "...", "result": "..." }

    -- Operator / HITL comments
    comments           JSONB       NOT NULL DEFAULT '[]'::JSONB,
    -- Each comment: { "author": "...", "role": "...", "text": "...", "ts": "ISO-DATETIME" }

    -- Classification metadata
    tags               TEXT[],
    resolved_by        VARCHAR(100),                   -- "AUTO" or operator username
    original_status    VARCHAR(40),                    -- AUTO_RESOLVED, HITL_RESOLVED, ESCALATED, …
    confidence_score   DOUBLE PRECISION,               -- model confidence at resolution time
    matched_sop_id     UUID,
    matched_sop_title  VARCHAR(255),

    -- RAG / embedding tracking
    embedding_ingested BOOLEAN     NOT NULL DEFAULT FALSE,
    -- set TRUE once the row has been added to the pgvector VectorStore

    -- Timestamps
    resolved_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Indices ──────────────────────────────────────────────────────────────────
CREATE INDEX idx_kb_tenant_id       ON resolved_incident_kb(tenant_id);
CREATE INDEX idx_kb_category        ON resolved_incident_kb(category);
CREATE INDEX idx_kb_severity        ON resolved_incident_kb(severity);
CREATE INDEX idx_kb_resolved_at     ON resolved_incident_kb(resolved_at DESC);
CREATE INDEX idx_kb_source_ticket   ON resolved_incident_kb(source_ticket_id);
CREATE INDEX idx_kb_embedding_flag  ON resolved_incident_kb(embedding_ingested)
    WHERE embedding_ingested = FALSE;   -- partial index for pending ingestion
CREATE INDEX idx_kb_incident_id     ON resolved_incident_kb(incident_id)
    WHERE incident_id IS NOT NULL;

-- ── Seed: migrate existing AUTO_RESOLVED incidents into the KB ───────────────
INSERT INTO resolved_incident_kb (
    incident_id, tenant_id, source_system, source_ticket_id,
    title, description, category, sub_category,
    severity, affected_systems,
    resolution_summary, original_status, confidence_score,
    matched_sop_id, resolved_by,
    resolved_at, created_at
)
SELECT
    id,
    COALESCE(tenant_id, '00000000-0000-0000-0000-000000000001'::UUID),
    source_system,
    source_ticket_id,
    title,
    description,
    category,
    sub_category,
    severity,
    affected_systems,
    'AUTO resolved by MCP pipeline (migrated from incidents table)',
    status,
    confidence_score,
    matched_sop_id,
    'AUTO',
    COALESCE(resolved_at, updated_at, created_at),
    COALESCE(created_at, now())
FROM incidents
WHERE status IN ('AUTO_RESOLVED', 'ESCALATED', 'GUARDRAILS_BLOCKED')
  AND source_ticket_id IS NOT NULL
  AND title IS NOT NULL;
