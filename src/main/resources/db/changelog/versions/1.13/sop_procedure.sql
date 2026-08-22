-- Approved remediation procedures: the record the HITL gate treats as authority to act.
-- Only an APPROVED row for the caller's tenant can back a remediation plan.

CREATE SCHEMA IF NOT EXISTS sop;

CREATE TABLE IF NOT EXISTS sop.sop_procedure (
    id                UUID PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    sop_id            VARCHAR(64)  NOT NULL,
    step_number       INTEGER      NOT NULL DEFAULT 1,
    title             VARCHAR(300) NOT NULL,
    description       VARCHAR(4000),
    match_keywords    VARCHAR(1000),
    action_key        VARCHAR(500) NOT NULL,
    approval_status   VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    requires_approval BOOLEAN      NOT NULL DEFAULT TRUE,
    execution_order   INTEGER      NOT NULL DEFAULT 10,
    reliability       DOUBLE PRECISION NOT NULL DEFAULT 0.70,
    success_count     INTEGER      NOT NULL DEFAULT 0,
    failure_count     INTEGER      NOT NULL DEFAULT 0,
    approved_by       VARCHAR(150),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT sop_procedure_step_unique UNIQUE (tenant_id, sop_id, step_number),
    CONSTRAINT sop_procedure_status_check CHECK (approval_status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    CONSTRAINT sop_procedure_reliability_check CHECK (reliability >= 0 AND reliability <= 1)
);

-- Every evidence lookup filters on (tenant_id, approval_status); nothing reads the table
-- without both, which is why this is the only index.
CREATE INDEX IF NOT EXISTS idx_sop_procedure_tenant_status
    ON sop.sop_procedure (tenant_id, approval_status);

-- External MCP servers this platform may connect to. Registry only: nothing dials these
-- yet, and a newly registered server is disabled until an operator enables it.
CREATE SCHEMA IF NOT EXISTS tools;

CREATE TABLE IF NOT EXISTS tools.mcp_server (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    transport   VARCHAR(32)  NOT NULL DEFAULT 'http',
    endpoint    VARCHAR(500) NOT NULL,
    description VARCHAR(1000),
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(150),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT mcp_server_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT mcp_server_transport_check CHECK (transport IN ('http', 'sse', 'stdio'))
);
