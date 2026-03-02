-- V10: Custom tools table — stores user-defined tools added via the UI.
-- These are loaded at startup by CustomToolLoader and registered into
-- McpToolRegistry alongside the built-in coded tools.

CREATE TABLE custom_tools (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(100) NOT NULL UNIQUE,
    category       VARCHAR(50)  NOT NULL,
    description    TEXT         NOT NULL,
    required_params JSONB        NOT NULL DEFAULT '[]',
    dangerous      BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by     VARCHAR(100),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_custom_tools_category ON custom_tools(category);
CREATE INDEX idx_custom_tools_enabled  ON custom_tools(enabled);
