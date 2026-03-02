-- V12: Script workspace table — stores user-created/edited/generated scripts.
-- Used by the Script Editor UI for dynamic script generation, validation, and execution.

CREATE TABLE script_workspace (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID,
    name                    VARCHAR(200)  NOT NULL,
    description             TEXT,
    script_content          TEXT          NOT NULL,
    language                VARCHAR(20)   NOT NULL DEFAULT 'bash',
    category                VARCHAR(50)   NOT NULL DEFAULT 'APPLICATION',
    target_host             VARCHAR(200),
    status                  VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
    last_validation_result  JSONB,
    last_execution_output   TEXT,
    last_execution_exit_code INTEGER,
    last_executed_at        TIMESTAMPTZ,
    created_by              VARCHAR(100),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_script_workspace_tenant   ON script_workspace(tenant_id);
CREATE INDEX idx_script_workspace_status   ON script_workspace(status);
CREATE INDEX idx_script_workspace_category ON script_workspace(category);
CREATE INDEX idx_script_workspace_language ON script_workspace(language);

